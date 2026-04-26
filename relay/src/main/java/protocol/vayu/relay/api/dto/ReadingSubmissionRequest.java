package protocol.vayu.relay.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ReadingSubmissionRequest(
        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{40}$")
        String reporter,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{16}$")
        String h3Index,

        @NotNull
        @Min(1)
        @Max(500)
        Integer aqi,

        @NotNull
        @Min(1)
        @Max(65535)
        Integer pm25,

        @Min(0)
        @Max(65535)
        Integer pm10,

        @Min(0)
        @Max(65535)
        Integer o3,

        @Min(0)
        @Max(65535)
        Integer no2,

        @Min(0)
        @Max(65535)
        Integer so2,

        @Min(0)
        @Max(65535)
        Integer co,

        @NotNull
        @Min(1)
        Long timestamp,

        @NotBlank
        @Pattern(regexp = "^0x[0-9a-fA-F]{130}$")
        String signature
) {
}
