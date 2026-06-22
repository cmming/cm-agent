package com.cmagent.core.tool;

import java.util.UUID;

public record ToolExecutionRequest(UUID toolId, String inputJson) {
}
