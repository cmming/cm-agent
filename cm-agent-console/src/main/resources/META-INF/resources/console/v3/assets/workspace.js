(function (root) {
    "use strict";

    let controller = null;
    let mountedSidebar = null;
    let navigationOpen = false;
    let contextOpen = false;
    let collapsed = false;
    let returnFocus = null;

    const byId = id => document.getElementById(id);
    const isV3 = () => document.body.dataset.consoleVersion === "v3";
    const isMobile = () => root.matchMedia("(max-width: 760px)").matches;
    const isContextOverlay = () => root.matchMedia("(max-width: 1050px)").matches;

    function visibleFocusables(region) {
        return Array.from(region?.querySelectorAll("a[href],button,input,select,textarea,summary,[tabindex]") || [])
            .filter(node => !node.disabled && node.tabIndex !== -1 && node.getClientRects().length > 0);
    }

    function syncAccessibility() {
        const sidebar = byId("v3Sidebar");
        const workspace = document.querySelector(".workspace");
        const panel = byId("v3ContextPanel");
        const navModal = navigationOpen && isMobile();
        const contextModal = contextOpen && isContextOverlay();
        if (workspace) workspace.inert = navModal || contextModal;
        if (sidebar) {
            // 抽屉移出屏幕仍存在于 DOM；关闭时必须同时移出键盘与辅助技术的访问范围。
            const sidebarHidden = isMobile() && !navigationOpen;
            sidebar.inert = contextModal || sidebarHidden;
            if (sidebarHidden) sidebar.setAttribute("aria-hidden", "true");
            else sidebar.removeAttribute("aria-hidden");
            if (navModal) { sidebar.setAttribute("role", "dialog"); sidebar.setAttribute("aria-modal", "true"); }
            else { sidebar.removeAttribute("role"); sidebar.removeAttribute("aria-modal"); }
        }
        if (panel) {
            if (contextModal) { panel.setAttribute("role", "dialog"); panel.setAttribute("aria-modal", "true"); }
            else { panel.removeAttribute("role"); panel.removeAttribute("aria-modal"); }
        }
        const scrim = byId("v3ContextScrim");
        if (scrim) scrim.hidden = !contextModal;
    }

    function openNavigation() {
        if (!isV3() || !byId("v3Sidebar")) return;
        if (!isMobile()) {
            collapsed = false;
            document.body.classList.remove("v3-sidebar-collapsed");
            return;
        }
        closeContext(false);
        navigationOpen = true;
        document.body.classList.add("v3-nav-open");
        byId("v3NavToggle")?.setAttribute("aria-expanded", "true");
        if (byId("v3NavScrim")) byId("v3NavScrim").hidden = false;
        syncAccessibility();
        byId("v3SidebarClose")?.focus();
    }

    function closeNavigation(restore = true) {
        const wasOpen = navigationOpen;
        navigationOpen = false;
        document.body.classList.remove("v3-nav-open");
        byId("v3NavToggle")?.setAttribute("aria-expanded", "false");
        if (byId("v3NavScrim")) byId("v3NavScrim").hidden = true;
        syncAccessibility();
        if (restore && wasOpen) byId("v3NavToggle")?.focus();
    }

    function openContext() {
        const panel = byId("v3ContextPanel");
        if (!isV3() || !panel) return;
        closeNavigation(false);
        if (!contextOpen) returnFocus = document.activeElement;
        contextOpen = true;
        panel.hidden = false;
        document.body.classList.add("v3-context-open");
        byId("v3ContextToggle")?.setAttribute("aria-expanded", "true");
        syncAccessibility();
        if (isContextOverlay()) byId("v3ContextClose")?.focus();
    }

    function closeContext(restore = true) {
        const wasOpen = contextOpen;
        contextOpen = false;
        if (byId("v3ContextPanel")) byId("v3ContextPanel").hidden = true;
        document.body.classList.remove("v3-context-open");
        byId("v3ContextToggle")?.setAttribute("aria-expanded", "false");
        syncAccessibility();
        if (restore && wasOpen && returnFocus?.isConnected) returnFocus.focus();
    }

    function onKeydown(event) {
        if (event.key === "Escape") {
            if (contextOpen) closeContext();
            else if (navigationOpen) closeNavigation();
            return;
        }
        const focusRegion = contextOpen && isContextOverlay() ? byId("v3ContextPanel")
            : navigationOpen && isMobile() ? byId("v3Sidebar") : null;
        if (event.key !== "Tab" || !focusRegion) return;
        const focusables = visibleFocusables(focusRegion);
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (!first) return;
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }

    function dispose() {
        // 多页面导航只替换 body，脚本实例会保留；每次离开页面必须释放原节点的监听和布局移动。
        root.CmAgentV3Chat?.dispose();
        controller?.abort();
        controller = null;
        closeContext(false);
        closeNavigation(false);
        mountedSidebar = null;
        returnFocus = null;
    }

    function mount() {
        if (!isV3()) return;
        const sidebar = byId("v3Sidebar");
        if (!sidebar) return;
        if (mountedSidebar === sidebar && controller) return;
        dispose();
        mountedSidebar = sidebar;
        controller = new AbortController();
        const options = {signal: controller.signal};
        document.body.classList.toggle("v3-sidebar-collapsed", collapsed);

        if (!byId("v3ContextScrim")) {
            const scrim = document.createElement("button");
            scrim.id = "v3ContextScrim";
            scrim.type = "button";
            scrim.className = "v3-context-scrim";
            scrim.setAttribute("aria-label", "关闭上下文");
            scrim.hidden = true;
            document.body.append(scrim);
        }
        const bind = (id, action) => byId(id)?.addEventListener("click", action, options);
        bind("v3NavToggle", () => navigationOpen ? closeNavigation() : openNavigation());
        bind("v3SidebarClose", () => {
            if (isMobile()) closeNavigation();
            else { collapsed = true; document.body.classList.add("v3-sidebar-collapsed"); byId("v3NavToggle")?.focus(); }
        });
        bind("v3NavScrim", () => closeNavigation());
        bind("v3ContextToggle", () => contextOpen ? closeContext() : openContext());
        bind("v3ContextClose", () => closeContext());
        bind("v3ContextScrim", () => closeContext());
        sidebar.addEventListener("click", event => {
            if (event.target.closest("a.nav-item")) closeNavigation(false);
        }, options);
        document.addEventListener("keydown", onKeydown, options);
        root.addEventListener("resize", () => {
            if (!isMobile() && navigationOpen) closeNavigation(false);
            syncAccessibility();
        }, options);
        const contextToggle = byId("v3ContextToggle");
        if (contextToggle) contextToggle.hidden = document.body.dataset.page !== "chatPage";
        root.CmAgentV3Chat?.mount();
        syncAccessibility();
    }

    root.CmAgentV3 = Object.freeze({mount, dispose, openNavigation, closeNavigation, openContext, closeContext});
})(window);
