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
package org.traccar.api;

import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.security.LoginService;
import org.traccar.api.security.PermissionsService;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.SessionHelper;
import org.traccar.media.VideoStreamManager;
import org.traccar.model.Device;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpSession;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Singleton
public class VideoSocketServlet extends JettyWebSocketServlet {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoSocketServlet.class);

    private static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    private final Config config;
    private final LoginService loginService;
    private final PermissionsService permissionsService;
    private final VideoStreamManager streamManager;

    @Inject
    public VideoSocketServlet(
            Config config, LoginService loginService,
            PermissionsService permissionsService, VideoStreamManager streamManager) {
        this.config = config;
        this.loginService = loginService;
        this.permissionsService = permissionsService;
        this.streamManager = streamManager;
    }

    @Override
    public void configure(JettyWebSocketServletFactory factory) {
        factory.setIdleTimeout(Duration.ofMillis(config.getLong(Keys.WEB_TIMEOUT)));
        factory.setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
        factory.setCreator((req, resp) -> {
            try {
                Map<String, List<String>> parameters = req.getParameterMap();

                Long userId = null;
                List<String> tokens = parameters.get("token");
                if (tokens != null && !tokens.isEmpty()) {
                    userId = loginService.login(tokens.get(0)).getUser().getId();
                } else if (SessionHelper.isSessionOriginValid(req.getHttpServletRequest())) {
                    userId = (Long) ((HttpSession) req.getSession()).getAttribute(SessionHelper.USER_ID_KEY);
                }
                if (userId == null) {
                    return null;
                }

                long deviceId = Long.parseLong(parameters.get("deviceId").get(0));
                int channel = Integer.parseInt(parameters.get("channel").get(0));
                permissionsService.checkPermission(Device.class, userId, deviceId);

                return new VideoSocket(streamManager, deviceId, channel);
            } catch (Exception e) {
                LOGGER.warn("Video socket rejected", e);
                return null;
            }
        });
    }

}
