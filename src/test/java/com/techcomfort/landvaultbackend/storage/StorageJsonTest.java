package com.techcomfort.landvaultbackend.storage;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Storage's wire values are lowercase like the rest of the API, through the Jackson Spring MVC actually uses. */
class StorageJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aStoredFileSerialisesLowercase() {
        StoredFileInfo file = new StoredFileInfo(UUID.randomUUID(), StorageVisibility.PRIVATE, "kyc-document",
                FileType.JPEG, "id.jpg", 10, "ready", null, UUID.randomUUID(), Instant.now(), Instant.now());

        String json = mapper.writeValueAsString(file);

        assertThat(json).contains("\"visibility\":\"private\"").contains("\"fileType\":\"jpeg\"")
                .doesNotContain("PRIVATE").doesNotContain("JPEG");
    }

    @Test
    void inputIsAcceptedInEitherCase() {
        assertThat(mapper.readValue("\"public\"", StorageVisibility.class)).isEqualTo(StorageVisibility.PUBLIC);
        assertThat(mapper.readValue("\"PUBLIC\"", StorageVisibility.class)).isEqualTo(StorageVisibility.PUBLIC);
        assertThat(mapper.readValue("\"webp\"", FileType.class)).isEqualTo(FileType.WEBP);
        assertThat(mapper.readValue("\"PDF\"", FileType.class)).isEqualTo(FileType.PDF);
        assertThatThrownBy(() -> mapper.readValue("\"svg\"", FileType.class)).isInstanceOf(Exception.class);
    }
}
