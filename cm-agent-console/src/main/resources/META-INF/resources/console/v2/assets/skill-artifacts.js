(function (root, factory) {
    const api = factory();
    if (typeof module === "object" && module.exports) module.exports = api;
    if (root) root.CmAgentArtifacts = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
    function formatSize(size) {
        return size >= 1048576 ? (size / 1048576).toFixed(1) + " MiB" : size >= 1024 ? (size / 1024).toFixed(1) + " KiB" : size + " B";
    }
    function safeFilename(value) {
        return String(value || "生成文件").replace(/[\\/\x00-\x1f\x7f"]/g, "_");
    }
    function createManager({api, getSessionEpoch, document, urlApi = URL, schedule = setTimeout, unschedule = clearTimeout}) {
        const entries = new Set();
        const urls = new Map();
        let activeDownloads = 0;
        let observer;
        const node = (tag, text, className) => {
            const el = document.createElement(tag);
            el.textContent = text || "";
            if (className) el.className = className;
            return el;
        };
        const remove = (entry) => {entry.cancelled = true; entry.controller.abort(); entries.delete(entry);};
        function dispose() {
            observer?.disconnect();
            for (const entry of entries) remove(entry);
            for (const [url, timer] of urls) {unschedule(timer); urlApi.revokeObjectURL(url);}
            urls.clear();
        }
        function mount(container, path, {hideEmpty = false} = {}) {
            const Observer = document.defaultView?.MutationObserver;
            if (Observer && document.body) {
                observer ||= new Observer(() => {for (const entry of entries) if (!entry.container.isConnected) remove(entry);});
                observer.observe(document.body, {childList: true, subtree: true});
            }
            for (const previous of entries) if (!previous.container.isConnected || previous.container === container) remove(previous);
            const controller = new AbortController();
            const entry = {container, controller, session: getSessionEpoch(), cancelled: false};
            entries.add(entry);
            const current = () => !entry.cancelled && entry.session === getSessionEpoch() && container.isConnected;
            container.classList.add("skill-artifacts");
            const heading = node("h4", "生成文件");
            const message = node("p", "正在查询生成文件…", "field-help");
            message.setAttribute("aria-live", "polite");
            container.replaceChildren(heading, message);
            function failed(error) {
                if (!current() || error.name === "AbortError") return;
                message.textContent = error.message;
                message.className = "form-status";
                message.dataset.tone = "error";
                const retry = node("button", "重新查询", "button secondary");
                retry.type = "button";
                retry.addEventListener("click", () => mount(container, path, {hideEmpty}));
                container.append(retry);
            }
            const ready = api.request(path, {signal: controller.signal}).then((listing) => {
                if (!current()) return;
                if (listing.status === "DISABLED" || hideEmpty && listing.status === "EMPTY") {container.hidden = true; return;}
                container.hidden = false;
                const files = Array.isArray(listing.files) ? listing.files : [];
                message.textContent = files.length ? "文件按有效期保存，下载时校验当前权限。" : ({
                    PENDING: "运行尚未完成，生成文件将在成功后提供。",
                    FAILED: "运行未成功，生成文件未交付。",
                    EMPTY: "本次运行未生成文件。"
                }[listing.status] || "当前没有可下载文件。");
                if (!files.length) return;
                const list = node("ul", "", "skill-artifact-list");
                for (const file of files) {
                    const item = node("li", "", "skill-artifact-row");
                    const details = node("div", "", "skill-artifact-info");
                    details.append(node("strong", file.filename, "skill-artifact-name"));
                    const date = new Date(file.expiresAt);
                    const expiry = Number.isNaN(date.valueOf()) ? "—" : date.toLocaleString();
                    const format = String(file.filename).split(".").pop().toUpperCase();
                    details.append(node("span", format + " · " + formatSize(file.sizeBytes) + " · 有效至 " + expiry, "field-help"));
                    const button = node("button", file.status === "EXPIRED" ? "已过期" : "下载文件", "button secondary");
                    button.type = "button";
                    button.disabled = file.status !== "READY";
                    button.setAttribute("aria-label", "下载 " + file.filename);
                    button.addEventListener("click", async () => {
                        if (button.disabled || !current()) return;
                        if (activeDownloads >= 2) {
                            message.textContent = "已有两个文件正在下载，请等待完成后再下载此文件。";
                            message.className = "form-status";
                            message.dataset.tone = "warning";
                            message.scrollIntoView({block: "nearest"});
                            return;
                        }
                        activeDownloads++;
                        button.disabled = true;
                        button.textContent = "正在下载…";
                        button.setAttribute("aria-busy", "true");
                        try {
                            const blob = await api.request("/api/skill-artifacts/" + encodeURIComponent(file.id) + "/content",
                                {responseType: "blob", signal: controller.signal});
                            if (!current()) return;
                            const url = urlApi.createObjectURL(blob);
                            const link = node("a");
                            link.href = url;
                            link.download = safeFilename(file.filename);
                            document.body.append(link);
                            link.click();
                            link.remove();
                            const timer = schedule(() => {urlApi.revokeObjectURL(url); urls.delete(url);}, 1000);
                            urls.set(url, timer);
                            message.textContent = "文件已交给浏览器保存。";
                            message.dataset.tone = "success";
                        } catch (error) {
                            if (current() && error.name !== "AbortError") {
                                message.textContent = error.message;
                                message.dataset.tone = "error";
                                message.className = "form-status";
                                if (error.status === 410) file.status = "EXPIRED";
                            }
                        } finally {
                            activeDownloads--;
                            if (current()) {
                                button.disabled = file.status === "EXPIRED";
                                button.textContent = button.disabled ? "已过期" : "下载文件";
                                button.removeAttribute("aria-busy");
                            }
                        }
                    });
                    item.append(details, button);
                    list.append(item);
                }
                container.append(list);
            }).catch(failed);
            return {ready, dispose: () => remove(entry)};
        }
        return {mount, dispose};
    }
    return {createManager, formatSize, safeFilename};
});
