package site.omagotchi.ruleservice.core.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import site.omagotchi.ruleservice.core.message.Message;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalConnectionTest {

    @Test
    @DisplayName("deliver한 메시지를 poll로 수신한다")
    void deliversAndPollsMessage() throws InterruptedException {
        LocalConnection conn = new LocalConnection();
        Message msg = Message.of(Map.of("value", 1));

        conn.deliver(msg);
        Message polled = conn.poll();

        assertThat(polled).isEqualTo(msg);
    }

    @Test
    @DisplayName("여러 메시지를 deliver 하면 poll은 FIFO로 반환한다")
    void pollsInFifoOrder() throws InterruptedException {
        LocalConnection conn = new LocalConnection();
        Message msg1 = Message.of(Map.of("order", 1));
        Message msg2 = Message.of(Map.of("order", 2));
        Message msg3 = Message.of(Map.of("order", 3));

        conn.deliver(msg1);
        conn.deliver(msg2);
        conn.deliver(msg3);

        assertThat(conn.poll()).isEqualTo(msg1);
        assertThat(conn.poll()).isEqualTo(msg2);
        assertThat(conn.poll()).isEqualTo(msg3);
    }

    @Test
    @DisplayName("서로 다른 스레드에서 deliver/poll 해도 정상적으로 전달된다")
    void deliversAndPollsAcrossDifferentThreads() throws InterruptedException {
        LocalConnection conn = new LocalConnection();
        Message msg = Message.of(Map.of("value", 1));
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Message> result = new AtomicReference<>();

        Thread consumer = new Thread(() -> {
            try {
                result.set(conn.poll());
                received.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        Thread producer = new Thread(() -> {
            try {
                conn.deliver(msg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        boolean completed = received.await(2, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(result.get()).isEqualTo(msg);
    }

    @Test
    @DisplayName("deliver 전에 poll을 호출한 스레드는 메시지가 도착할 때까지 블로킹된다")
    void pollBlocksUntilMsgArrives() throws InterruptedException {
        LocalConnection conn = new LocalConnection();
        AtomicBoolean pollReturned = new AtomicBoolean(false);
        CountDownLatch pollStarted = new CountDownLatch(1);

        Thread consumer = new Thread(() -> {
            try {
                pollStarted.countDown();
                conn.poll();
                pollReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        pollStarted.await();

        Thread.sleep(300); // poll 호출은 됐지만 아직 메시지가 없는 구간
        assertThat(pollReturned.get()).isFalse();

        conn.deliver(Message.of(Map.of("value", 1)));
        consumer.join(2000);

        assertThat(pollReturned.get()).isTrue();
    }

    @Test
    @DisplayName("버퍼 상한에 도달하면 그다음 deliver는 공간이 생길 때까지 블로킹된다")
    void deliverBlocksWhenBufferIsFull() throws InterruptedException {
        LocalConnection conn = new LocalConnection(2);
        conn.deliver(Message.of(Map.of("order", 1)));
        conn.deliver(Message.of(Map.of("order", 2)));

        AtomicBoolean thirdDeliverReturned = new AtomicBoolean(false);

        Thread producer = new Thread(() -> {
            try {
                conn.deliver(Message.of(Map.of("order", 3)));
                thirdDeliverReturned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        Thread.sleep(300);
        assertThat(thirdDeliverReturned.get()).isFalse(); // 공간이 없어서 아직 블로킹 중이어야 함

        conn.poll(); // 공간 하나 확보
        producer.join(2000);

        assertThat(thirdDeliverReturned.get()).isTrue();
    }

    @Test
    @DisplayName("deliver 한 만큼 getBufferSize()가 정확히 반영된다")
    void reportsCurrentBufferSize() throws InterruptedException {
        LocalConnection conn = new LocalConnection();

        assertThat(conn.getBufferSize()).isZero();

        conn.deliver(Message.of(Map.of("order", 1)));
        conn.deliver(Message.of(Map.of("order", 2)));

        assertThat(conn.getBufferSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("버퍼 용량이 0 이하이면 IllegalArgumentException을 던진다")
    void invalidBufferCapacityThrowsException() {
        assertThatThrownBy(() -> new LocalConnection(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalConnection(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("close()하면 버퍼에 남아있던 메시지가 전부 폐기된다")
    void closeDiscardsRemainingMessages() throws InterruptedException {
        LocalConnection conn = new LocalConnection();
        conn.deliver(Message.of(Map.of("order", 1)));
        conn.deliver(Message.of(Map.of("order", 2)));

        conn.close();

        assertThat(conn.getBufferSize()).isZero();
    }
}