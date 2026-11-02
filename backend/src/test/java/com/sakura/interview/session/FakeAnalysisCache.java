package com.sakura.interview.session;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 分析缓存假实现：内存表，供单测断言命中与写入行为。 */
public class FakeAnalysisCache implements AnalysisCache {

    public final Map<String, String> saved = new HashMap<>();

    @Override
    public Optional<String> load(String cacheKey) {
        return Optional.ofNullable(saved.get(cacheKey));
    }

    @Override
    public void save(String cacheKey, String json) {
        saved.put(cacheKey, json);
    }
}
