package org.maglez.eop.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("STRIDE Categories")
class StrideCategoryTest {

    @Test
    void shouldHaveAllSixCategories() {
        StrideCategory[] values = StrideCategory.values();
        assertEquals(6, values.length);
    }

    @Test
    void shouldContainElevationOfPrivilege() {
        assertNotNull(StrideCategory.valueOf("ELEVATION_OF_PRIVILEGE"));
    }

    @Test
    void shouldContainSpoofing() {
        assertNotNull(StrideCategory.valueOf("SPOOFING"));
    }

    @Test
    void shouldContainTampering() {
        assertNotNull(StrideCategory.valueOf("TAMPERING"));
    }

    @Test
    void shouldContainRepudiation() {
        assertNotNull(StrideCategory.valueOf("REPUDIATION"));
    }

    @Test
    void shouldContainInformationDisclosure() {
        assertNotNull(StrideCategory.valueOf("INFORMATION_DISCLOSURE"));
    }

    @Test
    void shouldContainDenialOfService() {
        assertNotNull(StrideCategory.valueOf("DENIAL_OF_SERVICE"));
    }
}
