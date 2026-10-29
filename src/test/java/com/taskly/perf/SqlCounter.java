package com.taskly.perf;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Đếm (và đo thời gian) các câu SQL gửi tới Postgres — mọi đường (JdbcTemplate, Hibernate) đều qua DataSource —
 * để chứng minh N+1 / số round-trip mỗi request. start() → gọi API → stop() trả danh sách "ms | sql".
 */
@TestConfiguration(proxyBeanMethods = false)
public class SqlCounter {

    public record Statement(String sql, double millis) {
    }

    private static volatile List<Statement> sink;

    public static void start() {
        sink = java.util.Collections.synchronizedList(new ArrayList<>());
    }

    public static List<Statement> stop() {
        List<Statement> result = sink;
        sink = null;
        return result;
    }

    @Bean
    static BeanPostProcessor sqlCountingDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof DataSource dataSource) || Proxy.isProxyClass(bean.getClass())) {
                    return bean;
                }
                return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
                        new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                            Object result = call(dataSource, method, args);
                            return result instanceof Connection connection ? wrapConnection(connection) : result;
                        });
            }
        };
    }

    private static Connection wrapConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(connection.getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result = call(connection, method, args);
                    if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                        return wrapStatement((PreparedStatement) result, sql);
                    }
                    return result;
                });
    }

    private static PreparedStatement wrapStatement(PreparedStatement statement, String sql) {
        return (PreparedStatement) Proxy.newProxyInstance(statement.getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    boolean execute = method.getName().startsWith("execute");
                    long start = System.nanoTime();
                    try {
                        return call(statement, method, args);
                    } finally {
                        List<Statement> current = sink;
                        if (execute && current != null) {
                            current.add(new Statement(sql, (System.nanoTime() - start) / 1e6));
                        }
                    }
                });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
