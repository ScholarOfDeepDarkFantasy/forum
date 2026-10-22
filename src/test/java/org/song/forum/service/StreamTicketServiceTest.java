package org.song.forum.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ticket 的四个不变量：只能用一次、过期即废、空/未知值返回 null、过期项会被清理。
 * <p>这些分支在集成测试里很难稳定复现（要等真实时间），因此单独用短 TTL 单测。</p>
 */
class StreamTicketServiceTest {

    private static final Long USER_ID = 42L;

    @Test
    void 签发后可以消费一次并拿到用户id() {
        StreamTicketService service = new StreamTicketService(60_000);

        String ticket = service.issue(USER_ID);

        assertThat(ticket).isNotBlank().isNotEqualTo(service.issue(USER_ID));
        assertThat(service.consume(ticket)).isEqualTo(USER_ID);
    }

    @Test
    void 同一张ticket重复消费返回null() {
        StreamTicketService service = new StreamTicketService(60_000);
        String ticket = service.issue(USER_ID);

        assertThat(service.consume(ticket)).isEqualTo(USER_ID);
        assertThat(service.consume(ticket)).isNull();
    }

    @Test
    void 过期后消费返回null() throws InterruptedException {
        StreamTicketService service = new StreamTicketService(20);
        String ticket = service.issue(USER_ID);

        Thread.sleep(40);

        assertThat(service.consume(ticket)).isNull();
    }

    @Test
    void 空值与未知票据返回null() {
        StreamTicketService service = new StreamTicketService(60_000);

        assertThat(service.consume(null)).isNull();
        assertThat(service.consume("")).isNull();
        assertThat(service.consume("   ")).isNull();
        assertThat(service.consume("not-a-real-ticket")).isNull();
    }

    @Test
    void 下次签发时清理过期票据() throws InterruptedException {
        StreamTicketService service = new StreamTicketService(20);
        service.issue(USER_ID);
        service.issue(USER_ID);
        assertThat(service.ticketCount()).isEqualTo(2);

        Thread.sleep(40);
        service.issue(USER_ID);

        // 只剩新签发的这一张
        assertThat(service.ticketCount()).isEqualTo(1);
    }

    @Test
    void ttlSeconds向下取整到秒() {
        assertThat(new StreamTicketService(60_000).ttlSeconds()).isEqualTo(60);
        assertThat(new StreamTicketService(1_500).ttlSeconds()).isEqualTo(1);
    }
}
