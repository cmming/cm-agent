const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");

const skills = require(path.resolve(
    __dirname,
    "../../main/resources/META-INF/resources/console/v2/assets/skills.js"
));

test("迟到的技能详情响应不能覆盖当前选择", () => {
    let session = 1;
    const scope = skills.createRequestScope(() => session);
    const skillA = scope.issue("detail");
    const skillB = scope.issue("detail");

    assert.equal(scope.isCurrent(skillA), false);
    assert.equal(scope.isCurrent(skillB), true);

    session = 2;
    assert.equal(scope.isCurrent(skillB), false);
});

test("只有同一候选和映射修订的已通过试运行才能开启发布", () => {
    const trial = {
        status: "PASSED",
        qualifiesRelease: true,
        versionId: "candidate-v2",
        mappingRevision: 7
    };

    assert.equal(skills.canPublishTrial(trial, "candidate-v2", 7), true);
    assert.equal(skills.canPublishTrial({...trial, status: "NOT_TRIGGERED"}, "candidate-v2", 7), false);
    assert.equal(skills.canPublishTrial(trial, "candidate-v3", 7), false);
    assert.equal(skills.canPublishTrial(trial, "candidate-v2", 8), false);
});

test("从预检接口的 check 字段读取结构预检状态", () => {
    assert.equal(skills.preflightStatus({check: {status: "PASSED"}, items: []}), "PASSED");
    assert.equal(skills.preflightStatus({check: {status: "FAILED"}, items: []}), "FAILED");
    assert.equal(skills.preflightStatus({summary: {status: "PASSED"}}), "未知");
});

test("试运行 Agent 选择仅包含当前租户已启用且具有标识的 Agent", () => {
    const enabled = {id: "agent-1", name: "分析 Agent", enabled: true};
    assert.deepEqual(skills.eligibleTrialAgents([
        enabled,
        {id: "agent-2", name: "已停用 Agent", enabled: false},
        {name: "缺少标识", enabled: true},
        null
    ]), [enabled]);
    assert.deepEqual(skills.eligibleTrialAgents(null), []);
});
