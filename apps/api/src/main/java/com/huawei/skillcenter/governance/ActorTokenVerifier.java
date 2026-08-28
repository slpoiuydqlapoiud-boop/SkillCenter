package com.huawei.skillcenter.governance;

@FunctionalInterface
public interface ActorTokenVerifier {
    Actor verify(String token);
}
