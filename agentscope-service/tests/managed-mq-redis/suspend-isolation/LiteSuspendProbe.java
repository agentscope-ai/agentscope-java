/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.java.impl.consumer.LitePushConsumerImpl;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Raw SDK transport probe. Redis observes ordering; it never prevents T2 from executing. */
public final class LiteSuspendProbe {
    private static final String RUN = System.getenv("PROBE_RUN");
    private static final String POD = System.getenv().getOrDefault("HOSTNAME", "local");
    private static final String PREFIX = "suspend:" + RUN + ":";

    private static final String OBSERVE =
            """
            local s=KEYS[1]
            local kind=ARGV[1]
            local n=redis.call('INCR',KEYS[3])
            local phase=redis.call('HGET',s,'phase') or 'WAITING'
            local violation=0
            local action=kind
            if kind=='T1' then
              if phase=='DONE' then
                action='T1_SUCCESS'
                redis.call('HSETNX',s,'t1_success_seq',n)
                redis.call('HINCRBY',s,'t1_success',1)
              else
                action='T1_SUSPEND'
                redis.call('HINCRBY',s,'suspends',1)
              end
              redis.call('HSET',s,'last_t1_pod',ARGV[2])
              redis.call('HSETNX',s,'first_t1_pod',ARGV[2])
              local old=tonumber(redis.call('HGET',s,'max_attempt') or '0')
              if tonumber(ARGV[3])>old then redis.call('HSET',s,'max_attempt',ARGV[3]) end
            elseif kind=='T2' or kind=='T3' then
              if phase~='DONE' or not redis.call('HGET',s,'t1_success_seq') then violation=1 end
              if kind=='T3' and not redis.call('HGET',s,'t2_seq') then violation=1 end
              redis.call('HSETNX',s,string.lower(kind)..'_seq',n)
              redis.call('HINCRBY',s,string.lower(kind)..'_count',1)
            elseif kind=='RESUME' then
              redis.call('HSET',s,'phase','DONE')
              redis.call('HSETNX',s,'resume_seq',n)
              redis.call('HINCRBY',s,'resumes',1)
              phase='DONE'
            elseif kind=='FAIL' then
              redis.call('HINCRBY',s,'failures',1)
              redis.call('HSET',s,'last_failure_attempt',ARGV[3])
            elseif kind=='OTHER' then
              redis.call('HINCRBY',s,'others',1)
            elseif kind=='AFTER_FAIL' then
              redis.call('HINCRBY',s,'after_fail',1)
            end
            if violation==1 then redis.call('HINCRBY',s,'violations',1) end
            local event=n..'\t'..ARGV[5]..'\t'..ARGV[6]..'\t'..action..'\t'..ARGV[2]
              ..'\t'..ARGV[3]..'\t'..phase..'\t'..violation..'\t'..ARGV[4]
            redis.call('RPUSH',KEYS[2],event)
            return {phase,redis.call('HGET',s,'suspend_ms') or '500',event}
            """;

    public static void main(String[] args) throws Exception {
        if (RUN == null) throw new IllegalArgumentException("PROBE_RUN is required");
        var provider = ClientServiceProvider.loadService();
        var config =
                ClientConfiguration.newBuilder()
                        .setEndpoints(System.getenv("PROBE_ENDPOINT"))
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .setMaxStartupAttempts(20)
                        .enableSsl(false)
                        .build();
        if (args[0].equals("send")) {
            String topic = topic(args[1]);
            String caseId = args[2];
            try (var producer =
                    provider.newProducerBuilder()
                            .setClientConfiguration(config)
                            .setTopics(topic)
                            .build()) {
                for (int i = 3; i < args.length; i++) {
                    String kind = args[i];
                    String lite = "isolate-" + RUN + "-" + caseId;
                    if (kind.equals("OTHER")) lite += "-other";
                    var receipt =
                            producer.send(
                                    provider.newMessageBuilder()
                                            .setTopic(topic)
                                            .setLiteTopic(lite)
                                            .setKeys(caseId + "-" + kind)
                                            .addProperty("probe_case", caseId)
                                            .addProperty("probe_kind", kind)
                                            .setBody(kind.getBytes(StandardCharsets.UTF_8))
                                            .build());
                    System.out.println(
                            "PROBE_SEND\t" + caseId + "\t" + kind + "\t" + receipt.getMessageId());
                }
            }
            return;
        }
        String role = args[1];
        String group =
                role.equals("turn")
                        ? System.getenv("PROBE_TURN_GROUP")
                        : System.getenv("PROBE_RESUME_GROUP");
        var consumer =
                provider.newLitePushConsumerBuilder()
                        .setClientConfiguration(config)
                        .bindTopic(topic(role))
                        .setConsumerGroup(group)
                        .setConsumptionThreadCount(1)
                        .setMaxCacheMessageCount(128)
                        .setMaxCacheMessageSizeInBytes(4 * 1024 * 1024)
                        .setEnableFifoConsumeAccelerator(true)
                        .setMessageListener(view -> consume(view, role))
                        .build();
        int attempts = ((LitePushConsumerImpl) consumer).getRetryPolicy().getMaxAttempts();
        redis(
                "HSET",
                PREFIX + "meta:" + POD,
                "max_attempts",
                String.valueOf(attempts),
                "role",
                role,
                "sdk",
                "5.2.2",
                "sdk_location",
                ClientServiceProvider.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toString());
        Files.writeString(Path.of("/tmp/probe-ready"), "ready\n");
        System.out.println("PROBE_READY\t" + POD + "\t" + role + "\tmaxAttempts=" + attempts);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        consumer.close();
                                    } catch (IOException failure) {
                                        failure.printStackTrace();
                                    }
                                }));
        new CountDownLatch(1).await();
    }

    private static String topic(String role) {
        return System.getenv(role.equals("turn") ? "PROBE_TURN_TOPIC" : "PROBE_RESUME_TOPIC");
    }

    private static ConsumeResult consume(MessageView view, String role) {
        try {
            String caseId = view.getProperties().get("probe_case");
            String kind = view.getProperties().get("probe_kind");
            if (caseId == null || kind == null || role.equals("resume") != kind.equals("RESUME"))
                throw new IllegalArgumentException("Unexpected isolated probe message");
            var result =
                    (List<?>)
                            redis(
                                    "EVAL",
                                    OBSERVE,
                                    "3",
                                    PREFIX + "case:" + caseId,
                                    PREFIX + "events",
                                    PREFIX + "seq",
                                    kind,
                                    POD,
                                    String.valueOf(view.getDeliveryAttempt()),
                                    view.getMessageId().toString(),
                                    String.valueOf(System.currentTimeMillis()),
                                    caseId);
            System.out.println("PROBE\t" + result.get(2));
            if (kind.equals("FAIL")) return ConsumeResult.FAILURE;
            if (kind.equals("T1") && !result.get(0).equals("DONE"))
                return ConsumeResultSuspend.of(
                        Duration.ofMillis(Long.parseLong((String) result.get(1))));
            // T2/T3 are never blocked by an application guard. Any early invocation is a failure.
            return ConsumeResult.SUCCESS;
        } catch (Exception failure) {
            System.err.println("PROBE_ERROR\t" + POD + "\t" + failure);
            failure.printStackTrace();
            return ConsumeResult.FAILURE;
        }
    }

    private static Object redis(String... command) throws IOException {
        String[] address = System.getenv("PROBE_REDIS").split(":", 2);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(address[0], Integer.parseInt(address[1])), 3000);
            socket.setSoTimeout(3000);
            var bytes = new ByteArrayOutputStream();
            bytes.write(("*" + command.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String part : command) {
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                bytes.write(("$" + value.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                bytes.write(value);
                bytes.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            socket.getOutputStream().write(bytes.toByteArray());
            socket.getOutputStream().flush();
            return read(new BufferedInputStream(socket.getInputStream()));
        }
    }

    private static Object read(BufferedInputStream input) throws IOException {
        int type = input.read();
        if (type < 0) throw new EOFException();
        String line = line(input);
        if (type == '+') return line;
        if (type == '-') throw new IOException("Redis: " + line);
        if (type == ':') return Long.parseLong(line);
        int length = Integer.parseInt(line);
        if (length < 0) return null;
        if (type == '$') {
            byte[] value = input.readNBytes(length);
            if (value.length != length || input.read() != '\r' || input.read() != '\n')
                throw new IOException("Truncated RESP value");
            return new String(value, StandardCharsets.UTF_8);
        }
        if (type == '*') {
            var values = new ArrayList<>();
            for (int i = 0; i < length; i++) values.add(read(input));
            return values;
        }
        throw new IOException("Unexpected RESP type " + type);
    }

    private static String line(BufferedInputStream input) throws IOException {
        var value = new ByteArrayOutputStream();
        for (int current; (current = input.read()) != -1; ) {
            if (current == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid RESP line");
                return value.toString(StandardCharsets.UTF_8);
            }
            value.write(current);
        }
        throw new EOFException();
    }
}
