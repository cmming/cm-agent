# CM Agent Design

Date: 2026-06-18
Status: Approved for implementation planning
License: Apache-2.0

## Purpose

CM Agent is an open source enterprise agent foundation built on AgentScope Java. The project targets production use by Java backend developers and enterprise platform teams. It provides both a reusable Java SDK/Spring Boot Starter and an independently deployable server with a thin management console.

The first release focuses on the core foundation: SDK, Starter, Agent runtime abstraction, tool governance, lightweight multi-tenancy, RBAC, audit logging, model configuration, and minimal console workflows. Business-specific products such as RAG, customer support bots, data analysis assistants, and workflow automation are future milestones built on top of this foundation.

## Current Context

The workspace starts as an empty repository at `F:\java\cm-agent`.

Verified project inputs:

- AgentScope Java provides a JVM agent framework with ReAct reasoning, Harness infrastructure, multi-agent orchestration, and MCP/A2A support.
- The AgentScope Java GitHub repository describes support for ReAct reasoning, tool calling, memory management, and multi-agent collaboration.
- Maven Central currently lists `io.agentscope` artifacts such as `agentscope-extensions` at `2.0.0-RC3`.

References:

- [AgentScope Java docs](https://java.agentscope.io/v2/en/intro.html)
- [AgentScope Java GitHub](https://github.com/agentscope-ai/agentscope-java)
- [Maven Central: agentscope-extensions](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions)

## Goals

- Build a modular monolith that can run as one deployable service while keeping SDK, Starter, runtime, persistence, adapter, and console boundaries clean.
- Expose stable CM Agent core interfaces so application code does not depend directly on AgentScope Java classes.
- Provide a default AgentScope Java v2 adapter while keeping room to support AgentScope 1.x or other runtimes later.
- Support lightweight multi-tenancy with `tenantId` on all tenant-owned data and tenant-level model/tool configuration.
- Provide JWT login, RBAC, API keys for service-to-service calls, and extension points for OIDC/SSO integration.
- Support DashScope native model configuration and OpenAI-compatible model configuration.
- Provide local tool governance and external MCP/A2A endpoint configuration.
- Support both MySQL and PostgreSQL through compatible schema design and automated migration tests.
- Deliver a thin management console with login, Agent management, tool governance, chat debugging, and audit log views.
- Provide production baseline features: structured logs, audit logs, health checks, Docker Compose, OpenAPI docs, migration scripts, Chinese README, and examples.

## Documentation Language Constraint

Production-facing documentation is Chinese-first. README files, quickstart guides, deployment guides, operations notes, configuration examples, release notes, and generated project documentation must be written in Chinese by default. English documentation can be added later as a translation, but it must not replace or block the Chinese production documentation baseline.

## Non-Goals For The First Release

- Full workflow orchestration.
- Dynamic plugin package loading.
- Billing, quotas, or commercial tenant plans.
- End-user chat product features beyond the debugging console.
- Knowledge base ingestion, RAG, embeddings, or vector search.
- Complex SSO administration UI.
- Multi-service deployment topology.
- Full plugin marketplace.

## Recommended Approach

Use a Maven multi-module modular monolith.

This gives the project a production-friendly shape without making the first release too heavy. Developers can consume the SDK/Starter, platform teams can deploy the server, and contributors can understand module boundaries without operating many services. The architecture keeps future service extraction possible by keeping contracts explicit at module boundaries.

Rejected alternatives:

- SDK/Starter-only first release: simpler, but weaker as an enterprise platform and harder to demonstrate.
- Multi-service platform first release: production-looking, but too heavy for an early open source project and harder for contributors to run.

## Module Structure

### `cm-agent-api`

Public API contracts shared by SDK, server, console, and examples.

Responsibilities:

- Common DTOs.
- Error codes.
- Pagination request and response models.
- Tenant context model.
- Principal model.
- Audit event model.
- API response envelope if the project chooses a unified response style.

Constraints:

- No Spring Web dependency.
- No persistence dependency.
- No AgentScope Java dependency.

### `cm-agent-core`

Stable domain interfaces and core services.

Responsibilities:

- `AgentRuntime` abstraction.
- `AgentRunRequest`, `AgentRunResult`, and streaming/run event models.
- `ToolRegistry`.
- `ToolExecutor`.
- `ToolAuthorizationPolicy`.
- `PermissionEvaluator`.
- `AuditPublisher`.
- `ModelRegistry`.
- Tenant context propagation abstractions.
- Default in-memory registries useful for tests and local examples.

Constraints:

- No direct database access.
- No direct dependency on AgentScope Java implementation classes.
- Spring dependencies are limited to optional integration where needed; core logic should remain testable without starting Spring.

### `cm-agent-agentscope-adapter`

Default runtime implementation backed by AgentScope Java v2.

Responsibilities:

- Implement `AgentRuntime`.
- Convert `AgentDefinition` and `AgentRunRequest` into AgentScope runtime objects.
- Bridge CM Agent tool metadata to AgentScope tool calling.
- Map AgentScope events, tool calls, errors, and final responses back to CM Agent run events.
- Keep all AgentScope-specific types behind this module.

Version strategy:

- Pin AgentScope Java dependencies in dependency management.
- Treat `2.0.0-RC3` as the initial candidate version because it is visible on Maven Central as of 2026-06-18.
- Re-check AgentScope Java release notes before implementation begins and again before the first public release.
- Keep adapter-level contract tests so AgentScope changes do not leak into `core` or `server`.

### `cm-agent-persistence`

Database-backed repositories and migrations.

Responsibilities:

- Flyway migrations.
- Repository implementations for tenants, users, roles, permissions, API keys, model configs, Agent definitions, tool definitions, tool grants, conversations, messages, runs, tool calls, and audit events.
- MySQL and PostgreSQL compatibility tests.

Database strategy:

- Use portable column types where possible.
- Store structured metadata as text JSON with application-level serialization unless a database-neutral abstraction is introduced.
- Keep database-specific SQL isolated behind repository implementations or migration variants.
- Use Testcontainers for MySQL and PostgreSQL migration/repository tests.

### `cm-agent-spring-boot-starter`

Auto-configuration for embedding CM Agent in existing Java services.

Responsibilities:

- Configuration properties.
- Conditional auto-configuration for core services.
- Default security integration hooks.
- Default tool registration from Spring beans or annotations.
- Default audit publisher wiring.
- Optional adapter and persistence wiring.

Usage target:

Java backend teams can add the Starter and expose CM Agent capabilities inside their own Spring Boot application without deploying the standalone server.

### `cm-agent-server`

Independently deployable Spring Boot server.

Responsibilities:

- REST API.
- Authentication and token issuance.
- API key authentication for service-to-service calls.
- Tenant-aware request filters.
- RBAC enforcement.
- Agent management.
- Tool management.
- Chat debugging/run endpoints.
- Audit query endpoints.
- OpenAPI documentation.
- Actuator health checks.

### `cm-agent-console`

Thin management console.

Responsibilities:

- Login page.
- Agent list and edit pages.
- Tool governance page.
- Chat debugging page.
- Audit log page.

Constraints:

- The console uses the same REST APIs as external clients.
- The console does not bypass RBAC or tool authorization.
- The console is not a polished end-user chat product in the first release.

### `cm-agent-examples`

Runnable examples for users and contributors.

Responsibilities:

- Starter embedding example.
- Standalone server example.
- Local tool example.
- MCP/A2A endpoint configuration example.
- DashScope model configuration example.
- OpenAI-compatible model configuration example.

## Core Domain Model

### `Tenant`

Defines the lightweight multi-tenant boundary. Tenant-owned tables include `tenantId`. Tenant-level configuration includes model providers, enabled tools, and security defaults.

### `User`, `Role`, `Permission`

Default identity and authorization model for self-hosted deployments. JWT login issues tokens for console and API access. RBAC permissions protect server endpoints and sensitive operations.

Examples of permissions:

- `tenant:read`
- `tenant:update`
- `agent:read`
- `agent:write`
- `agent:run`
- `tool:read`
- `tool:grant`
- `audit:read`
- `apikey:write`

OIDC/SSO is not fully implemented in the first release, but the identity layer must allow an external identity provider to map users and roles later.

### `ApiKey`

Service-to-service credential scoped to a tenant and a set of permissions. API keys can be created, disabled, rotated, and audited.

### `AgentDefinition`

Configuration for an agent.

Fields:

- `id`
- `tenantId`
- `name`
- `description`
- `systemPrompt`
- `modelProviderId`
- `modelName`
- `temperature`
- `maxIterations`
- `enabled`
- `toolIds`
- `createdBy`
- `updatedBy`

### `ModelProvider` And `ModelConfig`

Tenant-level model provider configuration.

Supported first-release provider modes:

- DashScope native.
- OpenAI-compatible.

Secrets such as API keys are stored encrypted or delegated to a pluggable secret provider. Plaintext secrets are never returned through API responses.

### `ToolDefinition`

Metadata for local and external tools.

Fields:

- `id`
- `tenantId`
- `name`
- `description`
- `type`: `LOCAL`, `MCP`, or `A2A`
- `inputSchema`
- `riskLevel`: `LOW`, `MEDIUM`, or `HIGH`
- `enabled`
- `endpoint`
- `createdBy`
- `updatedBy`

### `ToolGrant`

Authorization binding that controls which tenant, Agent, role, or principal may call a tool.

The first release supports tenant-level and Agent-level grants. Role-aware grants can be represented in the model and implemented where RBAC data is available.

### `Conversation`, `Message`, `Run`, `ToolCall`

Operational records for chat debugging and traceability.

`Run` records:

- Requesting tenant and principal.
- Agent used.
- Status.
- Started and finished timestamps.
- Error code and message.
- Token usage fields where provider data is available.
- Cost estimate fields where pricing configuration is available.

`ToolCall` records:

- Tool id.
- Tool name.
- Input summary.
- Output summary.
- Status.
- Duration.
- Authorization decision.
- Error details.

### `AuditEvent`

Append-only audit record for production traceability.

Events include:

- Login success and failure.
- API key creation, disablement, and rotation.
- Tenant configuration changes.
- Model configuration changes.
- Agent creation, update, enablement, disablement, and run.
- Tool registration, enablement, disablement, and grant changes.
- Tool calls.
- Permission denials.
- Runtime errors.

## Primary Data Flow

1. A console user or API client sends a request with a JWT or API key.
2. The server resolves `TenantContext` and `Principal`.
3. RBAC checks the requested operation.
4. For Agent runs, the server loads `AgentDefinition`, tenant-level `ModelConfig`, and authorized `ToolDefinition` records.
5. The server creates a `Run` record.
6. `AgentRuntime.run()` receives a fully scoped request.
7. The AgentScope adapter maps the request into AgentScope Java v2 runtime objects.
8. Before each tool execution, `ToolAuthorizationPolicy` checks tenant, Agent, principal, and grant state.
9. Authorized local tools run in-process; MCP/A2A tools call configured external endpoints.
10. Tool calls, run events, errors, and final outputs are persisted.
11. `AuditPublisher` emits audit events for security-sensitive and operational actions.
12. The console chat debugging page displays messages, tool calls, and errors through the same REST APIs external clients use.

Design principle:

AgentScope Java runs the agent. CM Agent owns enterprise governance boundaries: tenant scoping, permission checks, tool grants, audit logging, API contracts, and deployment ergonomics.

## REST API Surface

### Authentication

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/auth/me`
- `POST /api/api-keys`
- `GET /api/api-keys`
- `POST /api/api-keys/{id}/disable`
- `POST /api/api-keys/{id}/rotate`

### Tenants

- `GET /api/tenants`
- `GET /api/tenants/{id}`
- `PATCH /api/tenants/{id}`
- `GET /api/tenants/{id}/model-configs`
- `POST /api/tenants/{id}/model-configs`
- `PATCH /api/tenants/{id}/model-configs/{configId}`
- `POST /api/tenants/{id}/model-configs/{configId}/disable`

### Agents

- `GET /api/agents`
- `POST /api/agents`
- `GET /api/agents/{id}`
- `PATCH /api/agents/{id}`
- `POST /api/agents/{id}/enable`
- `POST /api/agents/{id}/disable`

### Tools

- `GET /api/tools`
- `GET /api/tools/{id}`
- `POST /api/tools`
- `PATCH /api/tools/{id}`
- `POST /api/tools/{id}/enable`
- `POST /api/tools/{id}/disable`
- `GET /api/tools/{id}/grants`
- `POST /api/tools/{id}/grants`
- `DELETE /api/tools/{id}/grants/{grantId}`

### Runs And Chat Debugging

- `POST /api/agents/{id}/runs`
- `GET /api/runs/{id}`
- `GET /api/runs/{id}/messages`
- `GET /api/runs/{id}/tool-calls`
- `GET /api/conversations`
- `GET /api/conversations/{id}`

Streaming can be introduced through Server-Sent Events for run events. If streaming is not ready in the first implementation slice, synchronous run execution plus persisted events is acceptable for the first implementation plan.

### Audit

- `GET /api/audit-events`

Filters:

- Time range.
- Tenant.
- Principal.
- Agent.
- Tool.
- Event type.
- Status.

## Console Scope

The console is intentionally thin.

Pages:

- Login.
- Agent list.
- Agent editor.
- Tool governance.
- Chat debugging.
- Audit log.

Required behavior:

- All pages call the REST API.
- RBAC controls which pages and actions are visible.
- Chat debugging shows final response, message history, tool calls, authorization failures, and runtime errors.
- Audit log supports filters that match the audit API.

## Security And Governance

Authentication:

- JWT for users.
- API keys for service-to-service calls.
- OIDC/SSO extension point in the identity layer.

Authorization:

- RBAC protects API operations.
- Tool grants protect tool invocation.
- High-risk tools can require explicit grants before they are available to an Agent.

Secret handling:

- Model API keys and external endpoint credentials are never returned through APIs.
- Secret storage is abstracted so local encrypted storage can later be replaced by Vault, KMS, or cloud secret managers.

Audit:

- All administrative changes and Agent/tool runtime actions emit audit events.
- Permission denials and tool authorization denials are auditable.

Tenant isolation:

- Every tenant-owned query filters by `tenantId`.
- Server request context carries `tenantId` from authenticated principal or API key.
- Tests must include cross-tenant access denial cases.

## Persistence And Migration

Supported databases:

- MySQL.
- PostgreSQL.

Migration approach:

- Flyway migrations define the schema.
- Repository tests run against both MySQL and PostgreSQL using Testcontainers.
- Schema names and indexes avoid database-specific features unless hidden behind explicit migration variants.

First-release tables:

- `tenants`
- `users`
- `roles`
- `permissions`
- `user_roles`
- `role_permissions`
- `api_keys`
- `model_configs`
- `agent_definitions`
- `tool_definitions`
- `tool_grants`
- `conversations`
- `messages`
- `runs`
- `tool_calls`
- `audit_events`

## Testing Strategy

### Unit Tests

Targets:

- Permission evaluation.
- Tenant context propagation.
- Tool authorization.
- Tool registry behavior.
- Agent run request assembly.
- Audit event creation.
- Model provider selection.

### Persistence Tests

Targets:

- Flyway migration success on MySQL.
- Flyway migration success on PostgreSQL.
- Repository CRUD.
- Tenant isolation.
- Audit event append/query.

### Server Integration Tests

Targets:

- Login and token refresh.
- API key authentication.
- RBAC success and denial paths.
- Agent CRUD.
- Tool grants.
- Agent run with a fake runtime.
- Audit event creation after administrative and runtime actions.

### Adapter Contract Tests

Targets:

- CM Agent request to AgentScope adapter input mapping.
- Tool call bridge.
- Error mapping.
- Run event mapping.

These tests use fake model/runtime behavior where possible so CI does not depend on live LLM credentials.

### Console Smoke Tests

Targets:

- Login.
- Create or edit Agent.
- Grant tool.
- Run chat debugging request.
- View audit log.

## Production Baseline

The first release must include:

- Structured application logs.
- Audit events persisted to the database.
- Spring Boot Actuator health endpoint.
- OpenAPI documentation.
- Docker Compose for server, MySQL, and PostgreSQL development profiles.
- Local development configuration.
- Example configuration for DashScope native models.
- Example configuration for OpenAI-compatible models.
- Chinese README with quickstart, architecture overview, and production notes.
- Chinese production documentation for deployment, operations, configuration, and release notes.
- Apache-2.0 license file.

## First Implementation Slice

The implementation plan should produce a working thin vertical slice:

1. Maven multi-module skeleton.
2. Core interfaces and domain models.
3. Spring Boot Starter auto-configuration.
4. Persistence migrations for MySQL and PostgreSQL.
5. Server authentication, tenant context, and RBAC baseline.
6. Agent definition CRUD.
7. Tool definition and grant management.
8. Fake runtime-backed Agent run endpoint.
9. AgentScope adapter module with contract tests and a guarded integration path.
10. Audit event publishing and query.
11. Minimal console pages for login, Agent editing, tool governance, chat debugging, and audit viewing.
12. Examples and documentation.

## Acceptance Criteria

- A developer can run the standalone server locally through documented commands.
- A developer can include the Starter in a Spring Boot example and register a local tool.
- MySQL and PostgreSQL migrations pass in automated tests.
- A tenant admin can log in, configure an Agent, grant a tool, run a chat debugging request, and see audit records.
- A service client can call the run API with an API key scoped to a tenant.
- Cross-tenant reads and writes are denied in tests.
- Tool calls are denied when the Agent lacks a grant.
- AgentScope Java-specific types do not appear in `cm-agent-core` public interfaces.
- Public Chinese documentation clearly explains the first-release scope and non-goals.

## Risks And Mitigations

Risk: AgentScope Java v2 APIs can change while still in RC.

Mitigation: isolate all direct AgentScope usage in `cm-agent-agentscope-adapter`, pin dependency versions, and test adapter contracts.

Risk: Dual database support increases first-release complexity.

Mitigation: keep schema portable, avoid database-specific JSON features in the first release, and require Testcontainers coverage for both databases.

Risk: Console work can expand beyond the foundation.

Mitigation: limit the console to management and debugging pages that validate backend capabilities.

Risk: Security scope can become too large.

Mitigation: implement JWT, RBAC, API keys, and OIDC extension points first; defer full SSO administration UI.

Risk: Tool execution can become unsafe in enterprise environments.

Mitigation: require tool metadata, risk level, explicit grants, pre-execution authorization, and auditable tool call records.
