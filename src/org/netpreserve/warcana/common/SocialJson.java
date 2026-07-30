package org.netpreserve.warcana.common;

import tools.jackson.databind.json.JsonMapper;

public class SocialJson {
    public static final JsonMapper mapper = JsonMapper.builder()
            .build();

}
