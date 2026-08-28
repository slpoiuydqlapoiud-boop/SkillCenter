package com.huawei.skillcenter.governance;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActorOrganizationClaimsContractTest {
    @Test
    void twoArgumentActorKeepsLocalCompatibility() {
        Actor actor = new Actor("alice", "developer");

        assertEquals(Set.of(), actor.teamIds());
        assertFalse(actor.teamClaimsAuthoritative());
    }

    @Test
    void organizationTeamsAreBoundedImmutableAndDeterministic() {
        Actor actor = new Actor("alice", "developer",
                new LinkedHashSet<>(Set.of("team-b", "team-a")), true);

        assertEquals(Set.of("team-a", "team-b"), actor.teamIds());
        assertTrue(actor.teamClaimsAuthoritative());
        assertThrows(UnsupportedOperationException.class, () -> actor.teamIds().add("team-c"));
        assertThrows(IllegalArgumentException.class,
                () -> new Actor("alice", "developer", Set.of("bad team"), true));
    }

    @Test
    void teamClaimConfigurationIsBoundedAndOptional() {
        ActorAuthenticationProperties.JwtProperties jwt = new ActorAuthenticationProperties.JwtProperties();

        assertEquals("teams", jwt.getTeamClaim());
        jwt.setTeamClaim("");
        assertEquals("", jwt.getTeamClaim());
        jwt.setTeamClaimRequired(true);
        assertTrue(jwt.isTeamClaimRequired());
        assertThrows(IllegalArgumentException.class, () -> jwt.setTeamClaim("team claim"));
        assertThrows(IllegalArgumentException.class, () -> jwt.setTeamClaim("x".repeat(129)));
    }
}
