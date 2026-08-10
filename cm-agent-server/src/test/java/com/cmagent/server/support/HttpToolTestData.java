package com.cmagent.server.support;

import com.cmagent.core.domain.HttpParameterDataType;
import com.cmagent.core.domain.HttpParameterDefinition;
import com.cmagent.core.domain.HttpParameterLocation;

import java.util.List;

/** 为服务端测试提供符合最新 HTTP 参数定义的最小数据。 */
public final class HttpToolTestData {
    private HttpToolTestData() {
    }

    /**
     * 返回一个可选查询参数，适用于只关注 HTTP 配置存在性的测试。
     */
    public static List<HttpParameterDefinition> singleOptionalQueryParameter() {
        return List.of(new HttpParameterDefinition(
                "unused", "", "unused", HttpParameterDataType.STRING, HttpParameterLocation.QUERY,
                "测试占位参数", false, "", "", List.of(),
                null, null, null, null, null, null, false
        ));
    }
}
