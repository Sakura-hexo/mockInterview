package com.liuzihe.interview.tool;

import java.util.List;

/**
 * JD 结构化解析结果：岗位方向、明确要求的技能、加分项。
 */
public record JdProfile(String direction, List<String> skills, List<String> bonus) {
}
