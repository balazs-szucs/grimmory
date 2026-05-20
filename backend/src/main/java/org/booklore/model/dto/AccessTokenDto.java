package org.booklore.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccessTokenDto {
    private String accessToken;

    private String refreshToken;

    private Long expires;

    @Builder.Default
    private Boolean isDefaultPassword = null;
}
