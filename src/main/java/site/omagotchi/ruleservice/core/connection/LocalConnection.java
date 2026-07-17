package site.omagotchi.ruleservice.core.connection;

import lombok.extern.slf4j.Slf4j;
import site.omagotchi.ruleservice.core.message.Message;

import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class LocalConnection implements Connection {

    private static final int DEFAULT_BUFFER_CAPACITY = 100;

    private final String id;
    private final LinkedBlockingQueue<Message> buffer;

    public LocalConnection() {
        this(DEFAULT_BUFFER_CAPACITY);
    }

    public LocalConnection(int bufferCapacity) {

        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("버퍼 용량은 1 이상이어야 합니다.");
        }

        this.id = UUID.randomUUID().toString();
        this.buffer = new LinkedBlockingQueue<>(bufferCapacity);
    }

    @Override
    public String getId() {
        return this.id;
    }

    @Override
    public void deliver(Message message) throws InterruptedException {
        this.buffer.put(message);
    }

    @Override
    public Message poll() throws InterruptedException {
        return buffer.take();
        // 인자 없는 poll()은 큐가 비어있으면 즉시 널 리턴하고 끝남. InterruptedException을 던지지 않고, 블로킹(대기)도 안 함.
        // 큐가 비어있을 때 메시지가 올 때까지 기다려야 함 -> take()
    }

    @Override
    public int getBufferSize() {
        return buffer.size();
    }

    @Override
    public void close() {
        log.debug("[LocalConnection] {} -> connection 종료, 잔여 메시지 {}건 폐기",
                this.id, this.buffer.size());
        this.buffer.clear();
    }
}