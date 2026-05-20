package org.booklore.config;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;

public class DslJsonConfig {
    public static final DslJson<Object> DSL_JSON = new DslJson<>(
            Settings.withRuntime()
                    .includeServiceLoader()
    );
}
