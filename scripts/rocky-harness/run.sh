#!/usr/bin/env bash
# 初次仅启动回环地址的受控单实例，无自动重启或流量开放；react为默认策略。
set -euo pipefail
base=/home/cm-agent-production
mode=${1:-react}
[[ "$mode" == react || "$mode" == harness ]] || { echo '执行模式只能为react或harness' >&2; exit 1; }
"$base/scripts/preflight.sh"
if docker container inspect cm-agent-production >/dev/null 2>&1; then
 echo '专属容器已存在；先排空并正常关闭，不能自动覆盖或强杀' >&2; exit 1
fi
if ss -ltnH | grep -q ':18086 '; then echo '受控端口18086已占用' >&2; exit 1; fi
socket_group=$(stat -c '%g' /var/run/docker.sock)
# 可信宿主编排服务持有Docker连接，模型/技能容器不取得socket或宿主文件挂载。
docker run -d --name cm-agent-production --restart=no --network host --entrypoint java \
 --user 10001:10001 --group-add "$socket_group" --read-only \
 -e HOME=/tmp -e DOCKER_CONFIG=/tmp/.docker \
 --cap-drop=ALL --security-opt=no-new-privileges \
 --cpus=1 --memory=1024m --pids-limit=256 \
 --log-opt max-size=10m --log-opt max-file=3 \
 --tmpfs /tmp:rw,nosuid,nodev,size=128m,mode=700,uid=10001,gid=10001 \
 -v "$base/app/cm-agent-server.jar:/app/cm-agent-server.jar:ro" \
 -v "$base/config:/etc/cm-agent:ro" -v "$base/secrets:/run/cm-agent-secrets:ro" \
 -v "$base/artifacts:/srv/cm-agent-artifacts" \
 -v /usr/bin/docker:/usr/local/bin/docker:ro -v /var/run/docker.sock:/var/run/docker.sock \
 -w /app maven:3.9.9-eclipse-temurin-21 \
 -Xms128m -Xmx768m -jar /app/cm-agent-server.jar \
 --spring.profiles.active=production \
 --spring.config.additional-location=file:/etc/cm-agent/application-production.properties \
 --cm-agent.agentscope.execution-mode="$mode" >/dev/null
echo '已请求启动回环地址生产实例；尚未开放流量，需核查健康、数据模式和严格审计'
