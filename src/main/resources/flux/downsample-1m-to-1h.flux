// 1분 평균 → 1시간 평균
// createTaskEvery(name, flux, "1h", orgId)로 등록 (-task.every = 1시간)
from(bucket: "omagotchi-avg-1m")
    |> range(start: -task.every)
    |> filter(fn: (r) => r._field == "value")
    |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
    |> to(bucket: "omagotchi-avg-1h")
