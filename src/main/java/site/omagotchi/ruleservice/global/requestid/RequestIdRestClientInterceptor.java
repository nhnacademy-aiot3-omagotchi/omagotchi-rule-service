package site.omagotchi.ruleservice.global.requestid;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** 현재 HTTP·작업 범위의 Request ID 전파와 누락 시 신규 발급. */
public class RequestIdRestClientInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public @NonNull ClientHttpResponse intercept(
            HttpRequest request,
            byte @NonNull [] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        RequestId requestId = RequestIdContext.currentOrGenerate();

        request.getHeaders().set(RequestId.HEADER_NAME, requestId.value());

        return execution.execute(request, body);
    }
}
