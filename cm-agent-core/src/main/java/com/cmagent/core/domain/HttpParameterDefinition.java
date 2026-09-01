package com.cmagent.core.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 使用扁平节点描述 HTTP Tool 输入参数及其请求位置。
 *
 * <p>每个实例只描述单个节点的局部信息；父子关系由 {@code id}/{@code parentId} 表达，
 * 完整树结构和位置继承由服务端编译器统一校验。</p>
 *
 * @param id 参数节点标识，以字母开头且仅含字母数字下划线连字符，租户内唯一
 * @param parentId 父节点标识；空白表示顶层节点
 * @param name 参数名称（或 JSON Pointer 路径），不允许换行符
 * @param dataType 参数数据类型，决定约束项与是否允许子节点
 * @param requestLocation 参数写入的 HTTP 请求位置
 * @param description 面向控制台的参数说明
 * @param required 是否必填；缺失且无默认值时映射阶段报错
 * @param defaultValueJson 参数默认值的 JSON 文本；空白表示未配置
 * @param exampleValueJson 参数示例值的 JSON 文本，仅供控制台填充示例；空白表示未配置
 * @param enumValues 枚举值列表，不允许空白或重复；空列表表示非枚举参数
 * @param minLength 字符串最小长度；仅 STRING 类型生效
 * @param maxLength 字符串最大长度；仅 STRING 类型生效
 * @param minimum 数值下界；仅数值类型生效
 * @param maximum 数值上界；仅数值类型生效
 * @param minItems 数组最小元素数；仅 ARRAY 类型生效
 * @param maxItems 数组最大元素数；仅 ARRAY 类型生效
 * @param uniqueItems 数组元素是否必须唯一；仅 ARRAY 类型生效
 */
public record HttpParameterDefinition(
        String id,
        String parentId,
        String name,
        HttpParameterDataType dataType,
        HttpParameterLocation requestLocation,
        String description,
        boolean required,
        String defaultValueJson,
        String exampleValueJson,
        List<String> enumValues,
        Integer minLength,
        Integer maxLength,
        BigDecimal minimum,
        BigDecimal maximum,
        Integer minItems,
        Integer maxItems,
        boolean uniqueItems
) {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /**
     * 校验参数节点的局部不变量；树关系和位置继承由服务端编译器统一校验。
     */
    public HttpParameterDefinition {
        id = id == null ? "" : id.trim();
        parentId = parentId == null ? "" : parentId.trim();
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        defaultValueJson = defaultValueJson == null ? "" : defaultValueJson.trim();
        exampleValueJson = exampleValueJson == null ? "" : exampleValueJson.trim();
        enumValues = (enumValues == null ? List.<String>of() : enumValues).stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("参数 id 必须以字母开头且只能包含字母、数字、下划线或连字符");
        }
        if (id.equals(parentId)) {
            throw new IllegalArgumentException("参数不能引用自身作为父节点");
        }
        if (dataType == null) {
            throw new IllegalArgumentException("参数 dataType 不能为空");
        }
        validateRange(minLength, maxLength, "字符串长度");
        validateRange(minItems, maxItems, "数组长度");
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("minimum 不能大于 maximum");
        }
        if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("参数 name 不能包含换行符");
        }
        if (enumValues.stream().anyMatch(String::isBlank)
                || new HashSet<>(enumValues).size() != enumValues.size()) {
            throw new IllegalArgumentException("enumValues 不能包含空值或重复值");
        }
    }

    /**
     * @return 节点为顶层参数（无父节点）时返回 {@code true}
     */
    public boolean root() {
        return parentId.isBlank();
    }

    /**
     * @return 已配置默认值时返回 {@code true}；缺失输入将按默认值参与映射
     */
    public boolean hasDefaultValue() {
        return !defaultValueJson.isBlank();
    }

    /**
     * @return 已配置示例值时返回 {@code true}，示例值仅供控制台填充输入
     */
    public boolean hasExampleValue() {
        return !exampleValueJson.isBlank();
    }

    private static void validateRange(Integer minimum, Integer maximum, String fieldName) {
        if ((minimum != null && minimum < 0) || (maximum != null && maximum < 0)) {
            throw new IllegalArgumentException(fieldName + "不能为负数");
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException(fieldName + "最小值不能大于最大值");
        }
    }
}
