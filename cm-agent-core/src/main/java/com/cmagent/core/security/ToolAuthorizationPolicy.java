package com.cmagent.core.security;

import com.cmagent.api.PrincipalRef;
import com.cmagent.core.domain.ToolDefinition;
import com.cmagent.core.domain.ToolGrant;

import java.util.List;
import java.util.UUID;

public interface ToolAuthorizationPolicy {

    AuthorizationDecision check(PrincipalRef principal, UUID agentId, ToolDefinition tool, List<ToolGrant> grants);
}
