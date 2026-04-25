package com.pranav.collab_editor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Configures the RedisTemplate bean used throughout the application.
 *
 * Why configure this manually instead of using Spring Boot's auto-config?
 *   Spring Boot's default RedisTemplate uses JdkSerializationRedisSerializer
 *   which stores keys and values as binary blobs. This makes debugging via
 *   the Redis CLI very painful — you see garbled bytes instead of readable strings.
 *
 *   By using StringRedisSerializer for all four serializers, every key and
 *   value stored in Redis is a plain human-readable string. This means you
 *   can inspect presence data directly with:
 *     redis-cli HGETALL presence:{docId}
 *   and see actual JSON, not binary garbage.
 *
 * Redis key design for Phase 6:
 *   presence:{docId}   → Redis Hash
 *     field: userId    → value: JSON-serialized CursorDTO
 *
 * Example:
 *   HSET presence:doc-123 userA '{"userId":"userA","name":"Pranav","line":5,"ch":12}'
 *   HSET presence:doc-123 userB '{"userId":"userB","name":"Alice","line":2,"ch":7}'
 *   EXPIRE presence:doc-123 30
 */
@Configuration
public class RedisConfig {

    /**
     * Creates a RedisTemplate that serializes all keys and values as plain strings.
     *
     * Four serializers are set:
     *   keySerializer      → the top-level key (e.g. "presence:doc-123")
     *   valueSerializer    → top-level value (not used for hashes, but set for consistency)
     *   hashKeySerializer  → hash field name (e.g. "userA")
     *   hashValueSerializer → hash field value (e.g. the JSON cursor string)
     *
     * @param factory provided by Spring Boot from application.yml redis config
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        return template;
    }
}