package com.ecommerce.backend.org.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.backend.org.OrgPermission;
import com.ecommerce.backend.org.OrgRole;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The organisation role matrix of docs/modules/ORGANIZATIONS.md, section 5, as executable rules. */
class OrgRoleTest {

    @Test
    void ownersHoldEveryPermissionIncludingOnesAddedLater() {
        assertThat(OrgRole.OWNER.permissions()).isEqualTo(EnumSet.allOf(OrgPermission.class));
    }

    @Test
    void managersRunTheShopButCannotCloseIt() {
        assertThat(OrgRole.MANAGER.permissions()).containsExactlyInAnyOrder(
                OrgPermission.ORGANIZATION_READ, OrgPermission.ORGANIZATION_UPDATE, OrgPermission.MEMBER_MANAGE);
        assertThat(OrgRole.MANAGER.grants(OrgPermission.ORGANIZATION_CLOSE)).isFalse();
    }

    @Test
    void staffCanOnlyRead() {
        assertThat(OrgRole.STAFF.permissions()).containsExactly(OrgPermission.ORGANIZATION_READ);
    }

    @Test
    void everyPermissionIsHeldByAtLeastOneRoleAndEveryRoleCanRead() {
        for (OrgPermission permission : OrgPermission.values()) {
            assertThat(OrgRole.values()).as(permission.name()).anyMatch(role -> role.grants(permission));
        }
        for (OrgRole role : OrgRole.values()) {
            assertThat(role.grants(OrgPermission.ORGANIZATION_READ)).as(role.name()).isTrue();
        }
    }

    @Test
    void theRankRuleLetsOwnersManageEveryoneAndOthersOnlyThoseBelow() {
        assertThat(OrgRole.OWNER.canManage(OrgRole.OWNER)).isTrue();
        assertThat(OrgRole.OWNER.canManage(OrgRole.MANAGER)).isTrue();
        assertThat(OrgRole.MANAGER.canManage(OrgRole.STAFF)).isTrue();
        // A manager cannot create another manager, promote themselves, or touch an owner.
        assertThat(OrgRole.MANAGER.canManage(OrgRole.MANAGER)).isFalse();
        assertThat(OrgRole.MANAGER.canManage(OrgRole.OWNER)).isFalse();
        assertThat(OrgRole.STAFF.canManage(OrgRole.STAFF)).isFalse();
    }

    @Test
    void theFounderIsAnOwnerAddedByNobody() {
        OrganizationMember founder = OrganizationMember.founder(UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        assertThat(founder.getRole()).isEqualTo(OrgRole.OWNER);
        assertThat(founder.getAddedBy()).isNull();
        assertThat(founder.isNew()).isTrue();
        assertThat(founder.can(OrgPermission.ORGANIZATION_CLOSE)).isTrue();
    }
}
