/* spring-watch 渲染层 - 全局 SW 对象
   包含:API 客户端 / 上下文 / UI 工具 / ECharts 包装
   所有业务页通过 window.SW 访问,无构建 */

(function () {
    "use strict";

    // ============================ API 客户端 ============================
    const api = {
        _unwrap(resp) {
            if (resp && typeof resp === "object" && "code" in resp) {
                if (resp.code !== 200) {
                    throw new Error(resp.message || ("HTTP " + resp.code));
                }
                return resp.data;
            }
            return resp;
        },
        _qs(params) {
            if (!params) return "";
            const pairs = [];
            for (const k in params) {
                if (params[k] === undefined || params[k] === null) continue;
                pairs.push(encodeURIComponent(k) + "=" + encodeURIComponent(params[k]));
            }
            return pairs.length ? "?" + pairs.join("&") : "";
        },
        _pickRow(rows, tagFilters) {
            if (!rows || rows.length === 0) return null;
            if (!tagFilters) return rows[0];
            for (const row of rows) {
                const tags = row.tags || {};
                let match = true;
                for (const k in tagFilters) {
                    if (String(tags[k] || "") !== String(tagFilters[k])) { match = false; break; }
                }
                if (match) return row;
            }
            return rows[0];
        },
        async get(url, params) {
            const resp = await fetch(url + this._qs(params), { method: "GET", headers: { "Accept": "application/json" } });
            if (!resp.ok) throw new Error("HTTP " + resp.status + " " + resp.statusText);
            return this._unwrap(await resp.json());
        },
        async post(url, body) {
            const resp = await fetch(url, {
                method: "POST",
                headers: { "Content-Type": "application/json", "Accept": "application/json" },
                body: body == null ? null : JSON.stringify(body)
            });
            if (!resp.ok) throw new Error("HTTP " + resp.status + " " + resp.statusText);
            return this._unwrap(await resp.json());
        },
        async put(url, body) {
            const resp = await fetch(url, {
                method: "PUT",
                headers: { "Content-Type": "application/json", "Accept": "application/json" },
                body: body == null ? null : JSON.stringify(body)
            });
            if (!resp.ok) throw new Error("HTTP " + resp.status + " " + resp.statusText);
            return this._unwrap(await resp.json());
        },
        async del(url) {
            const resp = await fetch(url, { method: "DELETE", headers: { "Accept": "application/json" } });
            if (!resp.ok) throw new Error("HTTP " + resp.status + " " + resp.statusText);
            return this._unwrap(await resp.json());
        },
        async latest(url, tagFilters) {
            const data = await this.get(url, tagFilters || {});
            const row = this._pickRow(data && data.rows, tagFilters);
            return row ? row.value : null;
        }
    };

    // ============================ 上下文 ============================
    const APPID_KEY = "spring_watch.currentAppid";
    const ctx = {
        getQueryAppid() {
            const m = location.search.match(/[?&]appid=(\d+)/);
            if (m) return m[1];
            return localStorage.getItem(APPID_KEY) || null;
        },
        setAppid(appid) {
            if (appid == null) localStorage.removeItem(APPID_KEY);
            else localStorage.setItem(APPID_KEY, String(appid));
            try { window.dispatchEvent(new CustomEvent("sw:appid-changed", { detail: { appid } })); } catch (e) {}
        },
        getAppid() { return this.getQueryAppid(); },
        async listApps() {
            return await api.get("/api/apps/active");
        },
        async getApp(id) {
            return await api.get("/api/apps/" + id);
        }
    };

    // ============================ UI 工具 ============================
    const ui = {
        _toastContainer: null,
        _ensureToastContainer() {
            if (!this._toastContainer) {
                this._toastContainer = document.createElement("div");
                this._toastContainer.className = "toast-container";
                document.body.appendChild(this._toastContainer);
            }
            return this._toastContainer;
        },
        toast(msg, type) {
            type = type || "info";
            const el = document.createElement("div");
            el.className = "toast " + type;
            el.textContent = msg;
            this._ensureToastContainer().appendChild(el);
            setTimeout(() => { el.style.opacity = "0"; el.style.transition = "opacity 0.3s"; setTimeout(() => el.remove(), 300); }, 3000);
        },
        formatBytes(n) {
            if (n == null || isNaN(n)) return "-";
            const abs = Math.abs(n);
            if (abs < 1024) return n.toFixed(0) + " B";
            if (abs < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
            if (abs < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
            return (n / 1024 / 1024 / 1024).toFixed(2) + " GB";
        },
        formatPercent(n, fixed) {
            if (n == null || isNaN(n)) return "-";
            const f = fixed == null ? 1 : fixed;
            return (n * 100).toFixed(f) + "%";
        },
        formatNumber(n, fixed) {
            if (n == null || isNaN(n)) return "-";
            const f = fixed == null ? 2 : fixed;
            return n.toFixed(f).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
        },
        formatMs(n) {
            if (n == null || isNaN(n)) return "-";
            if (n < 1) return (n * 1000).toFixed(1) + " μs";
            if (n < 1000) return n.toFixed(1) + " ms";
            return (n / 1000).toFixed(2) + " s";
        },
        formatTime(iso) {
            if (!iso) return "-";
            try { return new Date(iso).toLocaleString("zh-CN", { hour12: false }); }
            catch (e) { return iso; }
        },
        escapeHtml(s) {
            if (s == null) return "";
            return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
        }
    };

    // ============================ ECharts 包装 ============================
    const PALETTE = ["#2563eb", "#ea580c", "#16a34a", "#dc2626", "#64748b", "#8b5cf6", "#0891b2", "#db2777"];

    const chart = {
        _init(el) {
            const dom = typeof el === "string" ? document.querySelector(el) : el;
            if (!dom) throw new Error("chart element not found: " + el);
            if (dom.__echarts__) { try { dom.__echarts__.dispose(); } catch (e) {} }
            const inst = echarts.init(dom, null, { renderer: "canvas" });
            dom.__echarts__ = inst;
            const ro = new ResizeObserver(() => inst.resize());
            ro.observe(dom);
            dom.__ro__ = ro;
            return inst;
        },
        line(el, series, opts) {
            opts = opts || {};
            const inst = this._init(el);
            const data = series.map((s, i) => ({
                name: s.name,
                type: "line",
                smooth: opts.smooth !== false,
                showSymbol: false,
                areaStyle: opts.area ? { opacity: 0.2 } : null,
                stack: opts.stack ? "total" : null,
                emphasis: { focus: "series" },
                lineStyle: { width: 2 },
                itemStyle: { color: PALETTE[i % PALETTE.length] },
                data: (s.points || []).map(p => [p[0], p[1]])
            }));
            inst.setOption({
                color: PALETTE,
                grid: { left: 50, right: opts.dualAxis ? 50 : 20, top: 30, bottom: 50 },
                tooltip: { trigger: "axis", axisPointer: { type: "cross" } },
                legend: { top: 0, type: "scroll" },
                xAxis: { type: "time", boundaryGap: false, axisLabel: { fontSize: 11 } },
                yAxis: opts.dualAxis
                    ? [{ type: "value", name: opts.yAxisName || "", axisLabel: { fontSize: 11 } },
                       { type: "value", name: opts.yAxisName2 || "", axisLabel: { fontSize: 11 } }]
                    : { type: "value", name: opts.yAxisName || "", axisLabel: { fontSize: 11 } },
                dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 8 }],
                series: data
            }, true);
            return inst;
        },
        bar(el, data, opts) {
            opts = opts || {};
            const inst = this._init(el);
            const horizontal = !!opts.horizontal;
            const series = (data.series || []).map((s, i) => ({
                name: s.name,
                type: "bar",
                itemStyle: s.itemStyle || { color: PALETTE[i % PALETTE.length] },
                data: s.data || []
            }));
            const categoryAxis = {
                type: "category",
                data: data.categories || [],
                axisLabel: { fontSize: 11 }
            };
            const valueAxis = { type: "value", name: opts.yAxisName || "", axisLabel: { fontSize: 11 } };
            inst.setOption({
                color: PALETTE,
                grid: { left: horizontal ? 110 : 50, right: 20, top: 30, bottom: 50 },
                tooltip: { trigger: "axis", axisPointer: { type: "shadow" } },
                legend: { top: 0, type: "scroll" },
                xAxis: horizontal ? valueAxis : categoryAxis,
                yAxis: horizontal ? categoryAxis : valueAxis,
                series: series
            }, true);
            return inst;
        },
        pie(el, data, opts) {
            opts = opts || {};
            const inst = this._init(el);
            inst.setOption({
                color: PALETTE,
                tooltip: { trigger: "item", formatter: "{b}: {c} ({d}%)" },
                legend: { orient: opts.legendOrient || "vertical", right: 10, top: "center", type: "scroll" },
                series: [{
                    name: opts.name || "",
                    type: "pie",
                    radius: opts.donut ? ["45%", "70%"] : "70%",
                    center: ["38%", "50%"],
                    avoidLabelOverlap: true,
                    label: { show: opts.label !== false, formatter: "{b}\n{d}%" },
                    labelLine: { show: true },
                    data: (data || []).map((d, i) => ({ ...d, itemStyle: { color: PALETTE[i % PALETTE.length] } }))
                }]
            }, true);
            return inst;
        },
        numberCard(el, value, opts) {
            opts = opts || {};
            const dom = typeof el === "string" ? document.querySelector(el) : el;
            if (!dom) return;
            const num = (value == null || isNaN(value)) ? null : value;
            let level = "";
            if (num != null && opts.threshold != null) {
                if (opts.thresholdHigherIsBad !== false) {
                    if (num >= opts.threshold) level = "danger";
                    else if (num >= opts.threshold * 0.7) level = "warn";
                } else {
                    if (num <= opts.threshold) level = "danger";
                }
            }
            const formatted = (num == null) ? "-"
                : opts.format === "bytes" ? ui.formatBytes(num)
                : opts.format === "percent" ? ui.formatPercent(num, opts.fixed)
                : opts.format === "ms" ? ui.formatMs(num)
                : ui.formatNumber(num, opts.fixed);
            dom.innerHTML = ""
                + '<div class="title">' + ui.escapeHtml(opts.title || "") + '</div>'
                + '<div><span class="value ' + level + '">' + formatted + '</span>'
                + (opts.unit ? '<span class="unit">' + ui.escapeHtml(opts.unit) + '</span>' : '')
                + '</div>'
                + (opts.sub ? '<div class="sub">' + ui.escapeHtml(opts.sub) + '</div>' : '');
        },
        destroy(el) {
            const dom = typeof el === "string" ? document.querySelector(el) : el;
            if (!dom) return;
            if (dom.__echarts__) { try { dom.__echarts__.dispose(); } catch (e) {} dom.__echarts__ = null; }
            if (dom.__ro__) { try { dom.__ro__.disconnect(); } catch (e) {} dom.__ro__ = null; }
        }
    };

    // ============================ 暴露 ============================
    window.SW = { api, ctx, ui, chart };
})();
