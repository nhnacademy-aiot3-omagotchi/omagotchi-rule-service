// 1시간 평균 → 1일 평균 (버킷 이름은 InfluxInitializer가 설정값으로 치환)
import "timezone"
option location = timezone.location(name: "Asia/Seoul")

from(bucket: "${avg1hBucket}")
    |> range(start: -1d)
    |> filter(fn: (r) => r._field == "value")
    |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
    |> to(bucket: "${avg1dBucket}")
