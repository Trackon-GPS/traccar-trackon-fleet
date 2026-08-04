/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import org.traccar.BaseProtocol;
import org.traccar.PipelineBuilder;
import org.traccar.TrackerServer;
import org.traccar.config.Config;
import org.traccar.model.Command;

import jakarta.inject.Inject;

/**
 * Jimi IoT intelligent connected vehicle terminal (VG502 and similar OBD/CAN devices).
 *
 * <p>The wire format is a tailored variant of JT/T 808-2013. It is kept separate from
 * {@link Jt808Protocol} because the location extension ids and the transparent transmission
 * subcategories carry different meanings, so sharing a decoder would require model sniffing
 * on nearly every branch.
 */
public class TrackonobdProtocol extends BaseProtocol {

    @Inject
    public TrackonobdProtocol(Config config) {
        setSupportedDataCommands(
                Command.TYPE_CUSTOM,
                Command.TYPE_CONFIGURATION,
                Command.TYPE_REBOOT_DEVICE,
                Command.TYPE_FACTORY_RESET,
                Command.TYPE_POWER_OFF,
                Command.TYPE_POSITION_PERIODIC,
                Command.TYPE_ENGINE_STOP,
                Command.TYPE_ENGINE_RESUME,
                Command.TYPE_ALARM_ARM,
                Command.TYPE_ALARM_DISARM,
                Command.TYPE_OUTPUT_CONTROL,
                Command.TYPE_MESSAGE);
        addServer(new TrackerServer(config, getName(), false) {
            @Override
            protected void addProtocolHandlers(PipelineBuilder pipeline, Config config) {
                pipeline.addLast(new TrackonobdFrameEncoder());
                pipeline.addLast(new TrackonobdFrameDecoder());
                pipeline.addLast(new TrackonobdProtocolEncoder(TrackonobdProtocol.this));
                pipeline.addLast(new TrackonobdProtocolDecoder(TrackonobdProtocol.this));
            }
        });
    }

}
