package org.unlaxer.infra.volta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 境界値・異常系の追加カバレッジ for {@link AppRegistry}.
 *
 * <p>既存 {@link AppRegistryTest} は「id と host で解ける」「missing は empty」
 * の happy/sad を 1 ケースずつ押さえるだけ。ForwardAuth のホスト解決は
 * 壊れるとルーティング全体が壊れるため、以下を固定する:
 * <ul>
 *   <li>ホストのポート番号付き解決 ({@code admin.example.com:443})</li>
 *   <li>{@code appId} が空白文字のみのとき host 解決にフォールバック</li>
 *   <li>{@code host} が空白文字のみのとき empty になる</li>
 *   <li>空レジストリの {@code defaultAppUrl()} は empty</li>
 *   <li>id と host 両方渡したとき id が優先</li>
 * </ul>
 */
class AppRegistryResolveBoundaryTest {

    private static AppRegistry.AppPolicy app(String id, String url, String... roles) {
        return new AppRegistry.AppPolicy(id, url, List.of(roles));
    }

    @Test
    void resolveByHostStripsPort() {
        // ForwardAuth は Host ヘッダをそのまま渡すことが多く、port 付きで来る。
        // hostOf() は URL から host を抜くが、resolve() は port を strip する。
        AppRegistry registry = new AppRegistry(List.of(
                app("app-wiki", "https://wiki.example.com", "MEMBER"),
                app("app-admin", "https://admin.example.com", "ADMIN")
        ));

        // port 付き host でも hostMap に登録済みのベースホストに解けること。
        assertEquals("app-admin",
                registry.resolve(null, "admin.example.com:443").orElseThrow().id());
        assertEquals("app-wiki",
                registry.resolve(null, "wiki.example.com:8080").orElseThrow().id());
    }

    @Test
    void blankAppIdFallsBackToHost() {
        // appId が null でなく空白のみのときも host 解決に回る。
        // これを変えると ForwardAuth で appId を渡さない構成が壊れる。
        AppRegistry registry = new AppRegistry(List.of(
                app("app-wiki", "https://wiki.example.com", "MEMBER")
        ));

        assertEquals("app-wiki",
                registry.resolve("   ", "wiki.example.com").orElseThrow().id());
    }

    @Test
    void blankHostReturnsEmpty() {
        AppRegistry registry = new AppRegistry(List.of(
                app("app-wiki", "https://wiki.example.com", "MEMBER")
        ));

        // host が空白のみのときは host 解決を試みない。
        assertTrue(registry.resolve(null, "   ").isEmpty());
        assertTrue(registry.resolve("missing", "   ").isEmpty());
    }

    @Test
    void appIdTakesPrecedenceOverHost() {
        // 両方渡されたとき id が優先。host が一致しなくても id で解けること。
        AppRegistry registry = new AppRegistry(List.of(
                app("app-wiki", "https://wiki.example.com", "MEMBER"),
                app("app-admin", "https://admin.example.com", "ADMIN")
        ));

        assertEquals("app-wiki",
                registry.resolve("app-wiki", "admin.example.com").orElseThrow().id());
    }

    @Test
    void unknownAppIdFallsBackToHost() {
        // id が見つからなければ host で再解決する。これが壊れると
        // id ベースのリクエストで host による補正が効かなくなる。
        AppRegistry registry = new AppRegistry(List.of(
                app("app-wiki", "https://wiki.example.com", "MEMBER")
        ));

        assertEquals("app-wiki",
                registry.resolve("nonexistent", "wiki.example.com").orElseThrow().id());
    }

    @Test
    void defaultAppUrlEmptyWhenNoApps() {
        // 空レジストリの defaultAppUrl は empty。NPE にならないこと。
        AppRegistry empty = new AppRegistry(List.of());
        assertTrue(empty.defaultAppUrl().isEmpty());
    }

    @Test
    void defaultAppUrlReturnsFirst() {
        // ordered の先頭が default。順序は構築順を保つ。
        AppRegistry registry = new AppRegistry(List.of(
                app("app-first", "https://first.example.com", "MEMBER"),
                app("app-second", "https://second.example.com", "ADMIN")
        ));
        assertEquals("https://first.example.com", registry.defaultAppUrl().orElseThrow());
    }

    @Test
    void resolveWithBothNullReturnsEmpty() {
        AppRegistry registry = new AppRegistry(List.of(
                app("app-wiki", "https://wiki.example.com", "MEMBER")
        ));
        assertTrue(registry.resolve(null, null).isEmpty());
    }
}
