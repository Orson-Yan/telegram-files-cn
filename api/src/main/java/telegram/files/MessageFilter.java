package telegram.files;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.net.NetUtil;
import cn.hutool.core.util.*;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.introspection.JexlPermissions;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class MessageFilter {

    private static final Log log = LogFactory.get();

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_1 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_2 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_1);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_3 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_2);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS_4 = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_3);
                }
                return fieldValue;
            });

    private static final CopyOptions BEAN_TO_MAP_OPTIONS = CopyOptions.create()
            .setIgnoreNullValue(true)
            .setFieldValueEditor((_, fieldValue) -> {
                if (fieldValue == null) {
                    return null;
                }
                // For TdApi.Object types, we can convert them to Map recursively
                if (fieldValue instanceof TdApi.Object) {
                    return BeanUtil.beanToMap(fieldValue, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS_4);
                }
                return fieldValue;
            });

    private static final Map<String, JexlExpression> EXPR_CACHE = new ConcurrentHashMap<>();

    private static final JexlEngine JEXL_ENGINE = new JexlBuilder()
            .strict(true)
            .silent(false)
            .permissions(JexlPermissions.RESTRICTED
                    .compose("cn.hutool.core.*")
                    .compose("telegram.files.repository.*")
            )
            .namespaces(Map.<String, Object>ofEntries(
                    Map.entry("str", StrUtil.class),
                    Map.entry("array", ArrayUtil.class),
                    Map.entry("coll", CollUtil.class),
                    Map.entry("obj", ObjectUtil.class),
                    Map.entry("class", ClassUtil.class),
                    Map.entry("id", IdUtil.class),
                    Map.entry("char", CharUtil.class),
                    Map.entry("random", RandomUtil.class),
                    Map.entry("escape", EscapeUtil.class),
                    Map.entry("hex", HexUtil.class),
                    Map.entry("net", NetUtil.class),
                    Map.entry("zip", ZipUtil.class),
                    Map.entry("date", DateUtil.class),
                    Map.entry("re", ReUtil.class),
                    Map.entry("num", NumberUtil.class)
            ))
            .create();

    public static JexlExpression getExpression(String exprStr) {
        return EXPR_CACHE.computeIfAbsent(exprStr, JEXL_ENGINE::createExpression);
    }

    public static List<TdApi.Message> filter(List<TdApi.Message> messages, String exprStr) {
        Predicate<TdApi.Message> predicate = filter(exprStr);
        return messages.parallelStream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    public static Predicate<TdApi.Message> filter(String exprStr) {
        if (StrUtil.isBlank(exprStr)) {
            return _ -> true;
        }
        JexlExpression expression = getExpression(exprStr);
        return message -> {
            Map<String, Object> map = BeanUtil.beanToMap(message, new LinkedHashMap<>(16, 1), BEAN_TO_MAP_OPTIONS);
            TdApiHelp.getFileHandler(message).ifPresent(fileHandler -> {
                FileRecord fileRecord = fileHandler.convertFileRecord(0);
                map.put("f", fileRecord);
            });
            MapContext context = new MapContext(map);

            try {
                Object result = expression.evaluate(context);
                return result instanceof Boolean && (Boolean) result;
            } catch (Exception e) {
                log.warn("Failed to evaluate expression: {}, message id: {}, error: {}",
                        exprStr, message.id, e.getMessage());
                return false;
            }
        };
    }
}
