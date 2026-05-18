/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.werewolf.web;

import io.agentscope.examples.werewolf.localization.LocalizationBundle;
import io.agentscope.examples.werewolf.localization.LocalizationFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/game")
@CrossOrigin(origins = "*")
public class WerewolfWebController {

    private final LocalizationFactory localizationFactory;

    public WerewolfWebController(LocalizationFactory localizationFactory) {
        this.localizationFactory = localizationFactory;
    }

    @PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<GameEvent>> startGame(
            @RequestParam(name = "lang", defaultValue = "zh-CN") String lang) {

        LocalizationBundle bundle = localizationFactory.createBundle(lang);
        GameEventEmitter emitter = new GameEventEmitter();
        WerewolfWebGame game = new WerewolfWebGame(emitter, bundle);

        Mono.fromRunnable(
                        () -> {
                            try {
                                game.start();
                            } catch (Exception e) {
                                emitter.emitError("Game error: " + e.getMessage());
                            } finally {
                                emitter.complete();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return emitter.getEventStream()
                .map(
                        event ->
                                ServerSentEvent.<GameEvent>builder()
                                        .event(event.getType().name().toLowerCase())
                                        .data(event)
                                        .build());
    }
}
