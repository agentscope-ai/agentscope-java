// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import org.apache.jmeter.samplers.SampleResult as JMeterSampleResult

// Bound actual clients even if a cloud engine reserves more threads than requested.
if (ctx.getThreadNum() >= Integer.parseInt(props.getProperty('threads', '1'))) {
    Thread.sleep(1000)
    SampleResult.setIgnore()
    return
}

def base = props.getProperty('baseUrl').replaceAll('/+$', '')
def token = props.getProperty('authToken')
def agent = props.getProperty('agentId')
def environment = props.getProperty('environmentId')
def scenario = props.getProperty('scenario', 'short')
def seconds = Integer.parseInt(props.getProperty('toolSeconds', scenario == 'fifo' ? '30' : '5'))
def pollMillis = Long.parseLong(props.getProperty('pollMillis', '3000'))
def deadlineSeconds = Long.parseLong(props.getProperty('deadlineSeconds', '300'))
def batch = props.getProperty('batch', 'pts')
def marker = batch + '-' + UUID.randomUUID().toString()
def client = vars.getObject('maHttpClient')
if (client == null) {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    vars.putObject('maHttpClient', client)
}
def json = new JsonSlurper()
def api = { String label, String path, Object body = null, String key = null ->
    def sub = new JMeterSampleResult()
    sub.setSampleLabel(label)
    sub.sampleStart()
    try {
        def builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header('Authorization', 'Bearer ' + token).header('Content-Type', 'application/json')
                .header('X-Managed-Agents-Tenant', 'default').header('X-Managed-Agents-Namespace', 'default')
                .header('Managed-Agents-Extensions', 'turn-inputs-v1')
        if (key != null) builder.header('Idempotency-Key', key)
        if (body == null) builder.GET()
        else builder.POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        def response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        sub.setResponseCode(Integer.toString(response.statusCode()))
        sub.setSuccessful(response.statusCode() >= 200 && response.statusCode() < 300)
        // Keep tokens, request bodies and user outputs out of PTS sampling logs.
        if (!sub.isSuccessful()) throw new IllegalStateException(label + ' HTTP ' + response.statusCode())
        return json.parseText(response.body())
    } catch (Exception failure) {
        sub.setSuccessful(false)
        sub.setResponseMessage(failure.getClass().getSimpleName())
        throw failure
    } finally {
        sub.sampleEnd()
        SampleResult.addSubResult(sub)
    }
}
def submit = { String session, String text, String suffix ->
    def result = api('turn.admit', '/v1/sessions/' + session + '/events',
            [events:[[type:'user.message', content:[[type:'text',text:text]], managed_agents:[intent:'new_turn']]]] , marker + suffix)
    return result.data[0].managed_agents.receipt.id
}
try {
    if (!token || !agent || !environment || !['short', 'tool', 'fifo'].contains(scenario))
        throw new IllegalArgumentException('Missing properties or invalid scenario')
    def session = api('session.create', '/v1/sessions',
            [agent:agent, environment_id:environment, title:marker,
             metadata:[load_test_batch:batch, load_test_scenario:scenario]], marker + '-create').id
    def text = scenario == 'short' ? "请不要调用任何工具，仅原样回复 ${marker}。" :
            "只调用一次 longwait__long_wait，marker=${marker}，seconds=${seconds}。结果返回后完整报告 marker、call_id、started_at、finished_at，不要重复调用。"
    def t1 = submit(session, text, '-t1')
    def t2 = scenario == 'fifo' ? submit(session,
            "不要调用工具。根据上一轮真实结果报告 marker=${marker} 以及 call_id。", '-t2') : null
    def injection = marker + '-injected'
    boolean injected = false
    boolean t1Done = false
    boolean done = false
    long deadline = System.nanoTime() + deadlineSeconds * 1000000000L
    // Desynchronize status reads across load generators.
    Thread.sleep(ThreadLocalRandom.current().nextLong(Math.max(1L, pollMillis)))
    while (System.nanoTime() < deadline) {
        def first = api('turn.status', '/v1/sessions/' + session + '/turns/' + t1)
        if (first.status in ['failed','cancelled']) throw new IllegalStateException('T1 ' + first.status)
        t1Done = first.status == 'completed'
        if (scenario == 'fifo' && first.status == 'waiting_tool' && !injected) {
            api('turn.inject', '/v1/sessions/' + session + '/events',
                [events:[[type:'user.message',content:[[type:'text',text:'最终答复原样包含 ' + injection]],
                          managed_agents:[intent:'inject',turn_id:t1]]]], marker + '-inject')
            injected = true
        }
        if (t2) {
            def second = api('turn.status', '/v1/sessions/' + session + '/turns/' + t2)
            if (second.status in ['failed','cancelled']) throw new IllegalStateException('T2 ' + second.status)
            // Re-read T1: it may have completed between these two status requests.
            if (!t1Done && second.status != 'accepted')
                t1Done = api('turn.status', '/v1/sessions/' + session + '/turns/' + t1).status == 'completed'
            if (!t1Done && second.status != 'accepted') throw new IllegalStateException('T2 overtook T1')
            done = t1Done && second.status == 'completed'
        } else done = t1Done
        if (done) break
        Thread.sleep(pollMillis)
    }
    if (!done) throw new IllegalStateException('Turn deadline exceeded; accepted work may still be running')
    def readOutput = { String turn ->
        def path = '/v1/sessions/' + session + '/turns/' + turn + '/output?limit=100'
        def all = []
        while (path != null) {
            def page = api('turn.output', path)
            all.addAll(page.data)
            path = page.has_more ? '/v1/sessions/' + session + '/turns/' + turn +
                    '/output?limit=100&after=' + java.net.URLEncoder.encode(page.next_cursor.toString(), 'UTF-8') : null
        }
        return JsonOutput.toJson(all)
    }
    def output = readOutput(t1)
    if (!output.contains(marker)) throw new IllegalStateException('Missing marker in committed output')
    if (scenario != 'short' && !output.contains('call_id')) throw new IllegalStateException('Missing tool evidence')
    if (scenario == 'fifo') {
        if (!injected) throw new IllegalStateException('No waiting_tool window observed; use a longer tool for the suspension/injection probe')
        if (!output.contains(injection)) throw new IllegalStateException('Injected input was not applied')
        if (!readOutput(t2).contains(marker)) throw new IllegalStateException('T2 lost context')
    }
    SampleResult.setResponseCode('200')
    SampleResult.setSuccessful(true)
    SampleResult.setResponseMessage('Session completed and validated')
} catch (Exception failure) {
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode('500')
    SampleResult.setResponseMessage(failure.getClass().getSimpleName() + ': ' + failure.getMessage())
}
