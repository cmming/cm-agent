package com.cmagent.persistence;

import com.cmagent.core.domain.*;
import com.cmagent.core.repository.SandboxEndpointRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 用租户锁与短事务维护默认唯一性；所有查询和 CAS 都包含可信 tenant。 */
public class JdbcSandboxEndpointRepository implements SandboxEndpointRepository {
    /** 由配置持有，仓储不负责关闭连接池。 */ private final JdbcClient jdbc;
    /** 包含严格审计的同一数据源事务。 */ private final TransactionTemplate transactions;
    /** @param jdbc 命名参数客户端 @param transactions 当前数据源事务模板 */
    public JdbcSandboxEndpointRepository(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.transactions = transactions;
    }
    @Override public <T> T atomic(UUID tenant, Supplier<T> operation) {
        return transactions.execute(status -> {
            // 租户行始终存在，避免首次默认选择尚无行可锁的并发窗口；不在此锁内执行远程探测。
            jdbc.sql("SELECT id FROM tenants WHERE id=:tenant FOR UPDATE").param("tenant", tenant.toString())
                    .query(String.class).single();
            return operation.get();
        });
    }
    @Override public List<SandboxEndpoint> list(UUID tenant) {
        return jdbc.sql("SELECT * FROM skill_sandbox_endpoints WHERE tenant_id=:tenant AND deleted=false ORDER BY created_at,id")
                .param("tenant", tenant.toString()).query(this::map).list();
    }
    @Override public Optional<SandboxEndpoint> find(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM skill_sandbox_endpoints WHERE tenant_id=:tenant AND id=:id AND deleted=false")
                .param("tenant", tenant.toString()).param("id", id.toString()).query(this::map).optional();
    }
    @Override public void insert(SandboxEndpoint e) {
        jdbc.sql("INSERT INTO skill_sandbox_endpoints (id,tenant_id,display_name,backend,connection_mode,host,port,username,enabled,encrypted_credential,credential_version,revision,probe_revision,probed_at,probe_status,deleted,created_at,updated_at,ssh_auth_type) VALUES (:id,:tenant_id,:display_name,:backend,:connection_mode,:host,:port,:username,:enabled,:encrypted_credential,:credential_version,:revision,:probe_revision,:probed_at,:probe_status,:deleted,:created_at,:updated_at,:ssh_auth_type)")
                .params(parameters(e)).update();
    }
    @Override public boolean update(SandboxEndpoint e, long expected) {
        var params = parameters(e); params.put("expected", expected);
        return jdbc.sql("UPDATE skill_sandbox_endpoints SET display_name=:display_name,backend=:backend,connection_mode=:connection_mode,host=:host,port=:port,username=:username,enabled=:enabled,encrypted_credential=:encrypted_credential,credential_version=:credential_version,revision=:revision,probe_revision=:probe_revision,probed_at=:probed_at,probe_status=:probe_status,deleted=:deleted,updated_at=:updated_at,ssh_auth_type=:ssh_auth_type WHERE id=:id AND tenant_id=:tenant_id AND revision=:expected AND deleted=false")
                .params(params).update() == 1;
    }
    @Override public Optional<UUID> defaultId(UUID tenant) {
        return jdbc.sql("SELECT endpoint_id FROM skill_sandbox_defaults WHERE tenant_id=:tenant")
                .param("tenant", tenant.toString()).query(String.class).optional().map(UUID::fromString);
    }
    @Override public void setDefault(UUID tenant, UUID id) {
        if (id == null) { jdbc.sql("DELETE FROM skill_sandbox_defaults WHERE tenant_id=:tenant").param("tenant", tenant.toString()).update(); return; }
        if (find(tenant, id).isEmpty()) throw new IllegalArgumentException("沙箱端点不存在");
        Timestamp changed=Timestamp.from(Instant.now());
        if (jdbc.sql("UPDATE skill_sandbox_defaults SET endpoint_id=:id,updated_at=:at WHERE tenant_id=:tenant")
                .param("tenant", tenant.toString()).param("id", id.toString()).param("at",changed).update() == 0)
            jdbc.sql("INSERT INTO skill_sandbox_defaults(tenant_id,endpoint_id,created_at,updated_at) VALUES(:tenant,:id,:at,:at)")
                .param("tenant", tenant.toString()).param("id", id.toString()).param("at",changed).update();
    }
    private Map<String,Object> parameters(SandboxEndpoint e) {
        Map<String,Object> p = new HashMap<>();
        p.put("id",e.id().toString()); p.put("tenant_id",e.tenantId().toString()); p.put("display_name",e.displayName());
        p.put("backend",e.backend()); p.put("connection_mode",e.mode().name()); p.put("host",e.host()); p.put("port",e.port());
        p.put("username",e.username()); p.put("enabled",e.enabled()); p.put("encrypted_credential",e.encryptedCredential());
        p.put("ssh_auth_type",e.sshAuthType().name());
        p.put("credential_version",e.credentialVersion()); p.put("revision",e.revision()); p.put("probe_revision",e.probeRevision());
        p.put("probed_at",e.probedAt()==null?null:Timestamp.from(e.probedAt())); p.put("probe_status",e.probeStatus());
        p.put("deleted",e.deleted()); p.put("created_at",Timestamp.from(e.createdAt())); p.put("updated_at",Timestamp.from(e.updatedAt()));
        return p;
    }
    private SandboxEndpoint map(ResultSet r,int row) throws SQLException {
        var probed=r.getTimestamp("probed_at");
        return new SandboxEndpoint(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("tenant_id")),
            r.getString("display_name"),r.getString("backend"),SandboxConnectionMode.valueOf(r.getString("connection_mode")),
            r.getString("host"),r.getInt("port"),r.getString("username"),r.getBoolean("enabled"),
            r.getString("encrypted_credential"),r.getLong("credential_version"),r.getLong("revision"),r.getLong("probe_revision"),
            probed==null?null:probed.toInstant(),r.getString("probe_status"),r.getBoolean("deleted"),
            r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),SandboxSshAuthType.valueOf(r.getString("ssh_auth_type")));
    }
}
