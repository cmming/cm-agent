#!/usr/bin/env bash
#
# 管理 CM Agent 本机运行环境：启动、查看状态、关闭和重启。
#
# 默认管理 127.0.0.1:8080 服务端和已构建的本机客户端 fat JAR。
# 脚本使用后台进程并把日志写入运行目录；不会打印 .env、JWT、
# 数据库密码、模型密钥或设备私钥。开始前应已存在已构建的 fat JAR
# 和可用 Maven 依赖。

set -euo pipefail
umask 077

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DEFAULT_RUNTIME_DIR="${XDG_STATE_HOME:-${HOME:-}/.local/state}/cm-agent/local-runtime"

ACTION="status"
TARGET="both"
PROJECT_ROOT="$(dirname -- "$SCRIPT_DIR")"
RUNTIME_DIR="$DEFAULT_RUNTIME_DIR"
JAVA_HOME_OVERRIDE=""
MAVEN_OVERRIDE=""
CLIENT_STATE_DIR=""
CLIENT_PASSWORD_FILE=""
PORT=8080
TIMEOUT_SECONDS=120
BUILD=false
JAVA_EXECUTABLE=""

print_usage() {
    cat <<'EOF'
用法: scripts/manage-local-runtime.sh [动作] [选项]

动作:
  start       启动服务端和/或本机客户端
  status      查看运行状态（默认动作）
  stop        先关闭客户端，再关闭服务端
  restart     停止后重新启动

选项:
  -t, --target both|server|client   操作目标，默认 both
  -p, --port PORT                   服务端端口，默认 8080
  --timeout SECONDS                 启动等待秒数，默认 120
  --project-root DIR                项目根目录，默认脚本上一级
  --runtime-dir DIR                 运行包和日志目录
  --java-home DIR                   JDK 21 根目录
  --maven PATH                      Maven 可执行文件
  --client-state-dir DIR            本机客户端状态目录，传给 run --state-dir
  --client-password-file FILE       Linux 口令身份的非交互密码文件，文件权限必须为 600，并需要 python3
  --build                           仅为 start/restart 构建 fat JAR 并安装服务端依赖
  -h, --help                        显示帮助

示例:
  scripts/manage-local-runtime.sh status
  scripts/manage-local-runtime.sh start --target client
  scripts/manage-local-runtime.sh restart --target server
  scripts/manage-local-runtime.sh restart --build
EOF
}

die() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

require_value() {
    local option="$1"
    local value="${2:-}"
    [[ -n "$value" ]] || die "$option 缺少参数值。"
    printf '%s\n' "$value"
}

parse_arguments() {
    while (($# > 0)); do
        case "$1" in
            start|status|stop|restart)
                ACTION="$1"
                shift
                ;;
            -t|--target)
                TARGET="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --target=*)
                TARGET="${1#*=}"
                shift
                ;;
            -p|--port)
                PORT="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --port=*)
                PORT="${1#*=}"
                shift
                ;;
            --timeout)
                TIMEOUT_SECONDS="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --timeout=*)
                TIMEOUT_SECONDS="${1#*=}"
                shift
                ;;
            --project-root)
                PROJECT_ROOT="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --project-root=*)
                PROJECT_ROOT="${1#*=}"
                shift
                ;;
            --runtime-dir)
                RUNTIME_DIR="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --runtime-dir=*)
                RUNTIME_DIR="${1#*=}"
                shift
                ;;
            --java-home)
                JAVA_HOME_OVERRIDE="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --java-home=*)
                JAVA_HOME_OVERRIDE="${1#*=}"
                shift
                ;;
            --maven)
                MAVEN_OVERRIDE="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --maven=*)
                MAVEN_OVERRIDE="${1#*=}"
                shift
                ;;
            --client-state-dir)
                CLIENT_STATE_DIR="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --client-state-dir=*)
                CLIENT_STATE_DIR="${1#*=}"
                shift
                ;;
            --client-password-file)
                CLIENT_PASSWORD_FILE="$(require_value "$1" "${2:-}")"
                shift 2
                ;;
            --client-password-file=*)
                CLIENT_PASSWORD_FILE="${1#*=}"
                shift
                ;;
            --build)
                BUILD=true
                shift
                ;;
            -h|--help)
                print_usage
                exit 0
                ;;
            *)
                die "未知参数：$1"
                ;;
        esac
    done

    [[ "$TARGET" =~ ^(both|server|client)$ ]] || die "target 必须是 both、server 或 client。"
    [[ "$PORT" =~ ^[0-9]+$ ]] && ((PORT >= 1 && PORT <= 65535)) \
        || die "port 必须是 1 到 65535 之间的整数。"
    [[ "$TIMEOUT_SECONDS" =~ ^[0-9]+$ ]] && ((TIMEOUT_SECONDS >= 10 && TIMEOUT_SECONDS <= 600)) \
        || die "timeout 必须是 10 到 600 之间的整数。"
    [[ "$BUILD" == "false" || "$ACTION" == "start" || "$ACTION" == "restart" ]] \
        || die "--build 只能与 start 或 restart 一起使用。"
}

resolve_project_root() {
    [[ -d "$PROJECT_ROOT" ]] || die "项目目录不存在：$PROJECT_ROOT"
    PROJECT_ROOT="$(CDPATH= cd -- "$PROJECT_ROOT" && pwd -P)"
}

resolve_runtime_dir() {
    mkdir -p -- "$RUNTIME_DIR"
    RUNTIME_DIR="$(CDPATH= cd -- "$RUNTIME_DIR" && pwd -P)"
}

validate_client_password_file() {
    [[ -z "$CLIENT_PASSWORD_FILE" ]] && return 0
    [[ -f "$CLIENT_PASSWORD_FILE" && -r "$CLIENT_PASSWORD_FILE" ]] ||
        die "客户端密码文件不存在或不可读：$CLIENT_PASSWORD_FILE"
    [[ -s "$CLIENT_PASSWORD_FILE" ]] || die "客户端密码文件不能为空：$CLIENT_PASSWORD_FILE"
    local mode=""
    mode="$(stat -c '%a' "$CLIENT_PASSWORD_FILE" 2>/dev/null || true)"
    if [[ -n "$mode" ]] && (((8#$mode & 077) != 0)); then
        die "客户端密码文件必须仅对当前用户可读写（chmod 600）：$CLIENT_PASSWORD_FILE"
    fi
}

configure_java() {
    local candidate=""
    local detected_home=""
    if [[ -n "$JAVA_HOME_OVERRIDE" ]]; then
        candidate="$JAVA_HOME_OVERRIDE/bin/java"
        [[ -x "$candidate" ]] || die "Java 21 可执行文件不存在：$candidate"
        export JAVA_HOME="$JAVA_HOME_OVERRIDE"
    elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
        candidate="$JAVA_HOME/bin/java"
    else
        candidate="$(command -v java 2>/dev/null || true)"
        if [[ -n "$candidate" ]]; then
            detected_home="$(dirname -- "$(dirname -- "$candidate")")"
        fi
    fi
    [[ -n "$candidate" && -x "$candidate" ]] || die "未找到 Java；请安装 JDK 21 或指定 --java-home。"
    "$candidate" -version >/dev/null 2>&1 || die "Java 无法执行：$candidate"
    if [[ -n "$detected_home" ]]; then
        export JAVA_HOME="$detected_home"
    fi
    export PATH="$JAVA_HOME/bin:$PATH"
    JAVA_EXECUTABLE="$candidate"
}

resolve_maven() {
    local candidate=""
    if [[ -n "$MAVEN_OVERRIDE" ]]; then
        if [[ "$MAVEN_OVERRIDE" == */* ]]; then
            candidate="$MAVEN_OVERRIDE"
        else
            candidate="$(command -v "$MAVEN_OVERRIDE" 2>/dev/null || true)"
        fi
    else
        candidate="$(command -v mvn 2>/dev/null || true)"
    fi
    [[ -n "$candidate" && -x "$candidate" ]] || die "未找到 Maven；请安装 Maven 3.9+ 或指定 --maven。"
    printf '%s\n' "$candidate"
}

acquire_lock() {
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$RUNTIME_DIR/.manage-local-runtime.lock"
        flock -n 9 || die "另一个运行管理操作正在进行。"
    fi
}

process_is_java() {
    local pid="$1"
    local comm=""
    [[ -r "/proc/$pid/comm" ]] || return 1
    IFS= read -r comm < "/proc/$pid/comm" || return 1
    [[ "$comm" == "java" ]]
}

process_cmdline() {
    local pid="$1"
    tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true
}

get_server_pid() {
    local pid=""
    local cmdline=""
    while IFS= read -r pid; do
        [[ -n "$pid" ]] || continue
        process_is_java "$pid" || continue
        cmdline="$(process_cmdline "$pid")"
        if [[ "$cmdline" == *CmAgentServerApplication* ]]; then
            printf '%s\n' "$pid"
            return 0
        fi
    done < <(pgrep -f 'CmAgentServerApplication' 2>/dev/null || true)
    return 1
}

get_client_pids() {
    local pid=""
    local cmdline=""
    local found=false
    while IFS= read -r pid; do
        [[ -n "$pid" ]] || continue
        process_is_java "$pid" || continue
        cmdline="$(process_cmdline "$pid")"
        if [[ "$cmdline" =~ cm-agent-local-client.*-all\.jar[[:space:]]+run ]]; then
            printf '%s\n' "$pid"
            found=true
        fi
    done < <(pgrep -f 'cm-agent-local-client.*-all\.jar' 2>/dev/null || true)
    [[ "$found" == "true" ]]
}

pid_alive() {
    local pid="$1"
    local state=""
    kill -0 "$pid" 2>/dev/null || return 1
    state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]' || true)"
    [[ "$state" != Z* ]]
}

terminate_pid() {
    local pid="$1"
    local label="$2"
    local wait_seconds="$3"
    local deadline=$((SECONDS + wait_seconds))

    if ! pid_alive "$pid"; then
        return 0
    fi
    kill -TERM "$pid" 2>/dev/null || true
    while ((SECONDS < deadline)); do
        if ! pid_alive "$pid"; then
            return 0
        fi
        sleep 0.3
    done

    # 先发送 TERM 给应用完成关闭钩子；超时后再强制终止，避免长期占用端口。
    kill -KILL "$pid" 2>/dev/null || true
    sleep 0.5
    if pid_alive "$pid"; then
        printf '警告：%s 进程 %s 未能退出。\n' "$label" "$pid" >&2
        return 1
    fi
}

port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        [[ -n "$(ss -H -ltn "sport = :$PORT" 2>/dev/null || true)" ]]
        return
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
        return
    fi
    (exec 3<>"/dev/tcp/127.0.0.1/$PORT") >/dev/null 2>&1
}

server_healthy() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsS --max-time 3 "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1
        return
    fi
    if command -v wget >/dev/null 2>&1; then
        wget -q -T 3 -O /dev/null "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1
        return
    fi
    die "未找到 curl 或 wget，无法检查服务端健康状态。"
}

wait_server_healthy() {
    local launcher_pid="${1:-}"
    local deadline=$((SECONDS + TIMEOUT_SECONDS))
    while ((SECONDS < deadline)); do
        if server_healthy; then
            return 0
        fi
        if [[ -n "$launcher_pid" ]] && ! pid_alive "$launcher_pid"; then
            return 1
        fi
        sleep 2
    done
    return 1
}

load_dotenv() {
    local env_path="$PROJECT_ROOT/.env"
    [[ -f "$env_path" ]] || return 0

    local line=""
    local name=""
    local value=""
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%$'\r'}"
        if [[ "$line" =~ ^[[:space:]]*$ || "$line" =~ ^[[:space:]]*# ]]; then
            continue
        fi
        line="${line#"${line%%[![:space:]]*}"}"
        line="${line#export[[:space:]]}"
        if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*=[[:space:]]*(.*)$ ]]; then
            name="${BASH_REMATCH[1]}"
            value="${BASH_REMATCH[2]}"
            value="${value%"${value##*[![:space:]]}"}"
            if ((${#value} >= 2)); then
                if [[ "${value:0:1}" == "${value: -1}" &&
                    ("${value:0:1}" == "\"" || "${value:0:1}" == "'") ]]; then
                    value="${value:1:${#value}-2}"
                fi
            fi
            # .env 被视为数据而不是 shell 脚本；这里只解析赋值，避免执行其中的命令替换。
            export "$name=$value"
        fi
    done < "$env_path"
}

latest_jar() {
    local directory="$1"
    [[ -d "$directory" ]] || return 1
    find "$directory" -maxdepth 1 -type f -name 'cm-agent-local-client-*-all.jar' \
        -printf '%T@ %p\n' 2>/dev/null |
        sort -nr |
        sed -n '1p' |
        cut -d' ' -f2-
}

get_client_jar() {
    local jar=""
    jar="$(latest_jar "$RUNTIME_DIR" || true)"
    if [[ -n "$jar" && -f "$jar" ]]; then
        printf '%s\n' "$jar"
        return 0
    fi

    jar="$(latest_jar "$PROJECT_ROOT/cm-agent-local-client/target" || true)"
    [[ -n "$jar" && -f "$jar" ]] ||
        die "未找到客户端 fat JAR；请先运行 mvn -pl cm-agent-local-client -am -DskipTests package。"
    mkdir -p -- "$RUNTIME_DIR"
    cp -f -- "$jar" "$RUNTIME_DIR/"
    printf '%s\n' "$RUNTIME_DIR/$(basename -- "$jar")"
}

start_server() {
    local server_pid=""
    local launcher_pid=""
    local out_log="$RUNTIME_DIR/server-$PORT.out.log"
    local err_log="$RUNTIME_DIR/server-$PORT.err.log"
    local maven=""

    if server_pid="$(get_server_pid)"; then
        if server_healthy; then
            printf '服务端已运行：PID %s，http://127.0.0.1:%s\n' "$server_pid" "$PORT"
            return
        fi
        printf '服务端进程已存在但尚未健康：PID %s，继续等待...\n' "$server_pid"
        if wait_server_healthy ""; then
            printf '服务端已恢复健康：PID %s，HTTP 200\n' "$server_pid"
            return
        fi
        die "服务端进程存在但健康检查未通过：PID $server_pid"
    fi

    if port_in_use; then
        die "端口 $PORT 已被其他进程占用，不能启动 CM Agent 服务端。"
    fi

    configure_java
    maven="$(resolve_maven)"
    load_dotenv
    export CM_AGENT_LOCAL_CLIENT_ENABLED=true
    export CM_AGENT_LOCAL_CLIENT_AUDIENCE="http://127.0.0.1:$PORT"

    mkdir -p -- "$RUNTIME_DIR"
    nohup "$maven" -q -pl cm-agent-server spring-boot:run \
        >"$out_log" 2>"$err_log" 9>&- &
    launcher_pid=$!

    printf '服务端启动中：Maven PID %s，等待 http://127.0.0.1:%s/actuator/health\n' \
        "$launcher_pid" "$PORT"
    if ! wait_server_healthy "$launcher_pid"; then
        die "服务端在 $TIMEOUT_SECONDS 秒内未通过健康检查；请查看 $out_log 和 $err_log"
    fi
    if ! server_pid="$(get_server_pid)"; then
        server_pid="$launcher_pid"
    fi
    printf '服务端已启动：PID %s，HTTP 200\n' "$server_pid"
}

start_client() {
    local client_pid=""
    local existing_pids=""
    local java_executable=""
    local jar=""
    local -a client_command=()
    local out_log="$RUNTIME_DIR/client-run.out.log"
    local err_log="$RUNTIME_DIR/client-run.err.log"

    existing_pids="$(get_client_pids || true)"
    if [[ -n "$existing_pids" ]]; then
        client_pid="${existing_pids%%$'\n'*}"
        printf '本机客户端已运行：PID %s\n' "$client_pid"
        return
    fi
    if ! server_healthy; then
        die "$PORT 服务端未就绪，不能启动本机客户端。"
    fi

    configure_java
    java_executable="$JAVA_EXECUTABLE"
    jar="$(get_client_jar)"
    client_command=("$java_executable" -jar "$jar" run)
    if [[ -n "$CLIENT_STATE_DIR" ]]; then
        client_command+=(--state-dir "$CLIENT_STATE_DIR")
    fi
    mkdir -p -- "$RUNTIME_DIR"
    if [[ -n "$CLIENT_PASSWORD_FILE" ]]; then
        command -v python3 >/dev/null 2>&1 ||
            die "当前 Linux 环境缺少 python3，不能安全地为口令身份分配伪终端。"
        # 等待 Java 关闭终端回显并输出口令提示后再写入密码，避免 script/cat 提前回显密码。
        python3 - "$CLIENT_PASSWORD_FILE" "$out_log" "${client_command[@]}" \
            >/dev/null 2>"$err_log" 9>&- <<'PY' &
import os
import pty
import select
import subprocess
import sys

password_file, log_file = sys.argv[1], sys.argv[2]
command = sys.argv[3:]
prompt = "输入本机身份口令：".encode("utf-8")
with open(password_file, "rb") as handle:
    password = handle.readline().rstrip(b"\r\n")
if not password:
    raise SystemExit("empty client password file")

master, slave = pty.openpty()
process = subprocess.Popen(
    command,
    stdin=slave,
    stdout=slave,
    stderr=slave,
    close_fds=True,
    start_new_session=True,
)
os.close(slave)
sent = False
observed = b""
with open(log_file, "wb", buffering=0) as log:
    while True:
        if process.poll() is not None:
            break
        readable, _, _ = select.select([master], [], [], 0.2)
        if not readable:
            continue
        try:
            chunk = os.read(master, 4096)
        except OSError:
            break
        if not chunk:
            break
        log.write(chunk)
        if not sent:
            observed = (observed + chunk)[-1024:]
            if prompt in observed:
                os.write(master, password + b"\n")
                password = b""
                sent = True
                observed = b""

    while True:
        try:
            chunk = os.read(master, 4096)
        except OSError:
            break
        if not chunk:
            break
        log.write(chunk)

os.close(master)
raise SystemExit(process.wait())
PY
    else
        nohup "${client_command[@]}" \
            >"$out_log" 2>"$err_log" 9>&- &
    fi
    client_pid=$!
    sleep 3
    if ! pid_alive "$client_pid"; then
        die "本机客户端启动后立即退出；请查看 $out_log 和 $err_log"
    fi
    printf '本机客户端已启动：PID %s\n' "$client_pid"
}

stop_client() {
    local pids=()
    local pid=""
    while IFS= read -r pid; do
        if [[ -n "$pid" ]]; then
            pids+=("$pid")
        fi
    done < <(get_client_pids || true)

    if ((${#pids[@]} == 0)); then
        printf '本机客户端未运行。\n'
        return
    fi
    for pid in "${pids[@]}"; do
        terminate_pid "$pid" "本机客户端" 15 || true
    done
    if get_client_pids >/dev/null 2>&1; then
        die "本机客户端仍有进程未退出。"
    fi
    printf '本机客户端已关闭；配对关系不会删除。\n'
}

stop_server() {
    local server_pid=""
    local parent_pid=""
    local parent_cmdline=""
    local deadline=0

    if ! server_pid="$(get_server_pid)"; then
        printf '服务端未运行。\n'
        return
    fi

    parent_pid="$(ps -o ppid= -p "$server_pid" 2>/dev/null | tr -d '[:space:]' || true)"
    parent_cmdline=""
    if [[ -n "$parent_pid" && -r "/proc/$parent_pid/cmdline" ]]; then
        parent_cmdline="$(process_cmdline "$parent_pid")"
    fi

    terminate_pid "$server_pid" "服务端" 20 ||
        die "服务端进程 $server_pid 未能退出。"

    deadline=$((SECONDS + 20))
    while ((SECONDS < deadline)) && port_in_use; do
        sleep 0.3
    done

    if [[ -n "$parent_pid" && -r "/proc/$parent_pid/cmdline" &&
        "$parent_cmdline" == *spring-boot:run* && "$parent_cmdline" == *cm-agent-server* ]]; then
        terminate_pid "$parent_pid" "Maven" 10 || true
    fi

    if port_in_use; then
        die "端口 $PORT 仍未释放，请检查服务端父进程或日志。"
    fi
    printf '服务端已关闭。\n'
}

json_field() {
    local file="$1"
    local field="$2"
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$file" "$field" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    value = json.load(handle).get(sys.argv[2], "")
print("" if value is None else value)
PY
        return
    fi
    sed -n "s/.*\"$field\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" "$file" | sed -n '1p'
}

show_status() {
    local server_pid=""
    local client_pids=""
    local identity_path="${CLIENT_STATE_DIR:-$HOME/.cm-agent/local-client}/identity.json"
    local device_name=""
    local device_id=""
    local server_url=""
    local storage_kind=""

    if server_pid="$(get_server_pid)"; then
        if server_healthy; then
            printf '服务端：RUNNING，PID %s，HEALTHY (HTTP 200)\n' "$server_pid"
        else
            printf '服务端：RUNNING，PID %s，NOT HEALTHY\n' "$server_pid"
        fi
        printf '地址：http://127.0.0.1:%s\n' "$PORT"
    else
        printf '服务端：STOPPED\n'
    fi

    client_pids="$(get_client_pids | paste -sd ',' - || true)"
    if [[ -n "$client_pids" ]]; then
        client_pids="${client_pids//,/, }"
        printf '本机客户端：RUNNING，PID %s\n' "$client_pids"
    else
        printf '本机客户端：STOPPED\n'
    fi

    if [[ -f "$identity_path" ]]; then
        device_name="$(json_field "$identity_path" deviceName || true)"
        device_id="$(json_field "$identity_path" deviceId || true)"
        server_url="$(json_field "$identity_path" serverUrl || true)"
        storage_kind="$(json_field "$identity_path" storageKind || true)"
        printf '已配对设备：%s，%s\n' "${device_name:-未知}" "${device_id:-未知}"
        printf '服务端地址：%s，凭据：%s\n' "${server_url:-未知}" "${storage_kind:-未知}"
    else
        printf '已配对设备：无\n'
    fi
}

invoke_build() {
    local maven=""
    local built=""
    configure_java
    maven="$(resolve_maven)"

    printf '构建客户端 fat JAR...\n'
    if ! "$maven" -q -pl cm-agent-local-client -am -DskipTests package; then
        die "客户端构建失败。"
    fi
    printf '安装服务端依赖...\n'
    if ! "$maven" -q -pl cm-agent-server -am -DskipTests install; then
        die "服务端安装失败。"
    fi

    built="$(latest_jar "$PROJECT_ROOT/cm-agent-local-client/target" || true)"
    [[ -n "$built" && -f "$built" ]] || die "构建完成后未找到客户端 fat JAR。"
    mkdir -p -- "$RUNTIME_DIR"
    cp -f -- "$built" "$RUNTIME_DIR/"
    printf '运行包已更新：%s\n' "$(basename -- "$built")"
}

main() {
    parse_arguments "$@"
    resolve_project_root
    cd -- "$PROJECT_ROOT"
    resolve_runtime_dir
    if [[ "$ACTION" != "status" ]]; then
        acquire_lock
    fi
    validate_client_password_file

    case "$ACTION" in
        status)
            show_status
            ;;
        start)
            if [[ "$BUILD" == "true" ]]; then
                invoke_build
            fi
            if [[ "$TARGET" == "both" || "$TARGET" == "server" ]]; then
                start_server
            fi
            if [[ "$TARGET" == "both" || "$TARGET" == "client" ]]; then
                start_client
            fi
            show_status
            ;;
        stop)
            if [[ "$TARGET" == "both" || "$TARGET" == "client" ]]; then
                stop_client
            fi
            if [[ "$TARGET" == "both" || "$TARGET" == "server" ]]; then
                stop_server
            fi
            show_status
            ;;
        restart)
            if [[ "$TARGET" == "both" || "$TARGET" == "client" ]]; then
                stop_client
            fi
            if [[ "$TARGET" == "both" || "$TARGET" == "server" ]]; then
                stop_server
            fi
            if [[ "$BUILD" == "true" ]]; then
                invoke_build
            fi
            if [[ "$TARGET" == "both" || "$TARGET" == "server" ]]; then
                start_server
            fi
            if [[ "$TARGET" == "both" || "$TARGET" == "client" ]]; then
                start_client
            fi
            show_status
            ;;
    esac
}

main "$@"
