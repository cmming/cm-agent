package com.cmagent.core.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 使用扁平节点描述 HTTP Tool 输入参数及其请求位置。
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
     * @return 是否为顶层参数节点。
     */
    public boolean root() {
        return parentId.isBlank();
    }

    /**
     * @return 是否配置了默认值。
     */
    public boolean hasDefaultValue() {
        return !defaultValueJson.isBlank();
    }

    /**
     * @return 是否配置了示例值。
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
