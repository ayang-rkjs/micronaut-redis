/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.configuration.lettuce.cache

import io.lettuce.core.RedisFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisKeyAsyncCommands
import io.lettuce.core.api.async.RedisStringAsyncCommands
import io.lettuce.core.protocol.AsyncCommand
import io.lettuce.core.protocol.RedisCommand
import io.micronaut.context.ApplicationContext
import io.micronaut.context.BeanLocator
import io.micronaut.context.exceptions.ConfigurationException
import io.micronaut.core.convert.DefaultConversionService
import io.micronaut.inject.qualifiers.Qualifiers
import io.micronaut.runtime.ApplicationConfiguration
import spock.lang.AutoCleanup
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.nio.charset.Charset
import java.util.concurrent.ExecutionException

/**
 * @author Graeme Rocher
 * @since 1.0
 */
class RedisCacheSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext applicationContext = ApplicationContext.run(
            'redis.type':'embedded',
            'redis.caches.test.enabled':'true'
    )

    void "test read/write object from redis sync cache"() {
        when:
        RedisCache redisCache = applicationContext.getBean(RedisCache, Qualifiers.byName("test"))

        then:
        redisCache != null
        redisCache.getNativeCache() instanceof StatefulRedisConnection

        when:
        redisCache.put("test", new Foo(name: "test"))
        redisCache.put("two", new Foo(name: "two"))
        redisCache.put("three", 3)
        redisCache.put("four", "four")
        Foo foo = redisCache.get("test", Foo).get()
        then:
        foo != null
        foo.name == 'test'
        redisCache.async().get("two", Foo.class).get().get().name == "two"
        redisCache.async().get("three", Integer.class).get().get() == 3
        redisCache.async().get("four", String.class).get().get() == "four"

        when:
        redisCache.invalidate("test")

        then:
        !redisCache.get("test", Foo).isPresent()
        !redisCache.async().get("test", Foo).get().isPresent()
        redisCache.get("two", Foo).isPresent()

        when:
        redisCache.async().put("three", new Foo(name: "three")).get()
        Foo four = redisCache.async().get("four",Foo, {-> new Foo(name: "four")}).get()

        then:
        four != null
        redisCache.get("three", Foo).isPresent()
        redisCache.async().get("three", Foo).get().isPresent()
        redisCache.get("four", Foo).isPresent()

        when:
        redisCache.async().invalidate("three").get()

        then:
        !redisCache.async().get("three", Foo).get().isPresent()
        redisCache.get("four", Foo).isPresent()

        when:
        redisCache.invalidateAll()

        then:
        !redisCache.get("test", Foo).isPresent()
        !redisCache.get("two", Foo).isPresent()

        then:
        // invalidate an empty cache should not fail
        redisCache.invalidateAll()

        cleanup:
        applicationContext.stop()
    }

    void "test creating expiration after write policy that is not of type ExpirationAfterWritePolicy"() {
        given:
        ApplicationConfiguration appConfig = new ApplicationConfiguration()
        appConfig.setDefaultCharset(Charset.defaultCharset())
        RedisCacheConfiguration cacheConfig = new RedisCacheConfiguration("test3", appConfig)
        cacheConfig.setExpirationAfterWritePolicy("io.micronaut.configuration.lettuce.cache.TimeService")

        when:
        new RedisCache(
                new DefaultRedisCacheConfiguration(appConfig),
                cacheConfig,
                new DefaultConversionService(),
                applicationContext.getBean(BeanLocator.class)
        )

        then:
        thrown ConfigurationException
    }

    void "test creating expiration after write policy that does not exist"() {
        given:
        ApplicationConfiguration appConfig = new ApplicationConfiguration()
        appConfig.setDefaultCharset(Charset.defaultCharset())
        RedisCacheConfiguration cacheConfig = new RedisCacheConfiguration("test3", appConfig)
        cacheConfig.setExpirationAfterWritePolicy("io.micronaut.configuration.lettuce.cache.MissingClass")

        when:
        new RedisCache(
                new DefaultRedisCacheConfiguration(appConfig),
                cacheConfig,
                new DefaultConversionService(),
                applicationContext.getBean(BeanLocator.class)
        )

        then:
        thrown ConfigurationException
    }

    void "test creating expiration after write policy that exists but has no bean"() {
        given:
        ApplicationConfiguration appConfig = new ApplicationConfiguration()
        appConfig.setDefaultCharset(Charset.defaultCharset())
        RedisCacheConfiguration cacheConfig = new RedisCacheConfiguration("test3", appConfig)
        cacheConfig.setExpirationAfterWritePolicy("java.math.BigDecimal")

        when:
        new RedisCache(
                new DefaultRedisCacheConfiguration(appConfig),
                cacheConfig,
                new DefaultConversionService(),
                applicationContext.getBean(BeanLocator.class)
        )

        then:
        thrown ConfigurationException
    }

    @Requires({jvm.javaVersion.startsWith("1.8")}) // Because of the reflection run only on Java 8
    void "test exceptions"() {
        setup:
            def redisStringAsyncCommands = Mock(RedisStringAsyncCommands.class)
            RedisCache redisCache = applicationContext.getBean(RedisCache, Qualifiers.byName("test"))

            Field field = RedisCache.getDeclaredField("redisStringAsyncCommands")
            field.setAccessible(true)

            Field modifiersField = Field.class.getDeclaredField("modifiers")
            modifiersField.setAccessible(true)
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL)

            field.set(redisCache, redisStringAsyncCommands)

            def result = new AsyncCommand(Mock(RedisCommand))
            redisStringAsyncCommands.get(_) >> result
            result.completeExceptionally(new RuntimeException("XYZ"))
        when:
            def op = redisCache.async().get("two", Foo.class)
        then:
            op.isCompletedExceptionally()
        when:
            op.get()
        then:
            def e = thrown(ExecutionException)
            e.cause.message == "XYZ"

        cleanup:
            applicationContext.stop()
    }

    static class Foo implements Serializable {
        String name
    }
}
