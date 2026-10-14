#!/usr/bin/env bash
# 生产材料检查只输出文件名/状态，不输出任何 Secret 或连接串；失败前不启动应用。
set -euo pipefail
base=/home/cm-agent-production
expected=b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12
fail() { printf '前置检查拒绝：%s\n' "$1" >&2; exit 1; }
[[ $(id -u) == 0 ]] || fail '仅部署管理员可运行'
[[ -d "$base" && $(realpath "$base") == "$base" ]] || fail '部署目录不可用'
[[ $(sha256sum "$base/app/cm-agent-server.jar" | cut -d' ' -f1) == "$expected" ]] || fail '候选包摘要不匹配'
[[ -f "$base/config/application-production.properties" ]] || fail '缺少外部生产配置'
chronyc waitsync 6 0.1 0 5 >/dev/null || fail '主机时钟尚未同步'
docker image inspect maven:3.9.9-eclipse-temurin-21 >/dev/null 2>&1 || fail 'JDK21运行镜像不可用'
docker image inspect python:3.12-alpine >/dev/null 2>&1 || fail '固定Python镜像不可用'
[[ -S /var/run/docker.sock ]] || fail 'Docker本地连接不可用'
required=(cm-agent.config.external-jwt-secret cm-agent.config.external-jdbc-url
 cm-agent.config.external-jdbc-username cm-agent.config.external-jdbc-password
 cm-agent.model-credentials.encryption-key cm-agent.skills.sandbox.encryption-key
 cm-agent.skills.sandbox.artifacts.encryption-key)
missing=0
for name in "${required[@]}"; do
 path="$base/secrets/$name"
 if [[ ! -s "$path" || -L "$path" ]]; then printf '缺少受控Secret：%s\n' "$name"; missing=1; continue; fi
 [[ $(stat -c '%u' "$path") == 10001 ]] || fail "Secret所有者不正确：$name"
 [[ $(stat -c '%a' "$path") =~ ^(400|600)$ ]] || fail "Secret权限不正确：$name"
done
[[ $missing == 0 ]] || fail '生产Secret尚未供给；保留停机，不生成替代密钥'
python3 - "$base" <<'PY'
import base64,json,pathlib,sys
base=pathlib.Path(sys.argv[1]);secrets=base/'secrets'
names=['cm-agent.model-credentials.encryption-key','cm-agent.skills.sandbox.encryption-key','cm-agent.skills.sandbox.artifacts.encryption-key']
try:
    material={p.name:p.read_text() for p in secrets.iterdir() if p.is_file()}
    if any(not value or value!=value.strip() for value in material.values()):
        raise ValueError()
    keys=[base64.b64decode(material[n],validate=True) for n in names]
    jwt=material['cm-agent.config.external-jwt-secret']
    username=material['cm-agent.config.external-jdbc-username']
    password=material['cm-agent.config.external-jdbc-password']
    if any(len(k)!=32 for k in keys) or len(set(keys))!=3 or len(jwt.encode())<32 or 'dev' in jwt.lower():
        raise ValueError()
    if username.lower()=='root' or len(password.encode())<20:
        raise ValueError()
    proof=json.loads((base/'config/recovery-verification.json').read_text())
    expected='b456c963113a403d3907988404eb3761a82a81b4afc2df73525646d607959f12'
    if (proof.get('candidateSha256')!=expected or proof.get('modelsDecrypted')!=5
        or proof.get('endpointsDecrypted')!=1 or proof.get('artifactsAuthenticated')!=5
        or proof.get('backupRestoreVerified') is not True or proof.get('allInstancesStopped') is not True):
        raise ValueError()
except Exception:
    print('前置检查拒绝：Secret格式或真实恢复/实例停机证明缺失',file=sys.stderr)
    sys.exit(1)
PY
# 此处仅核对已明确的系统部署元数据，不能替代入口冻结和全实例停机证明。
drain=$(docker exec cm-agent-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --batch --skip-column-names -uroot cm_agent -e "SELECT COUNT(*) FROM runs WHERE status NOT IN ('\''SUCCEEDED'\'','\''FAILED'\'','\''DENIED'\'','\''CANCELLED'\''); SELECT COUNT(*) FROM skill_trials WHERE status NOT IN ('\''PASSED'\'','\''NOT_TRIGGERED'\'','\''FAILED'\''); SELECT COUNT(*) FROM runtime_checkpoints; SELECT COUNT(*) FROM tool_approval_requests WHERE status='\''PENDING'\''; SELECT COUNT(*) FROM information_schema.processlist WHERE DB='\''cm_agent'\'' AND ID<>CONNECTION_ID();"')
[[ "$drain" == $'0\n0\n0\n0\n0' ]] || fail '数据集或当前数据库连接未排空'
printf '生产前置检查通过；仍须由应用启动模式门禁核查\n'
