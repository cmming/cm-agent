package com.cmagent.core.domain;

/** 区分正式运行与技能验证运行，避免试运行污染正式历史。 */
public enum RunKind {
    /** 用户发起并进入正式运行历史的普通运行。 */
    NORMAL,
    /** 仅用于指定技能版本验证的受控试运行。 */
    TEST
}
