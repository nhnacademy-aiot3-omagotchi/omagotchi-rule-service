// 1시간 평균 → 1일 평균
// createTaskEvery(name, flux, "1d", orgId)로 등록 (-task.every = 1일)
from(bucket: "omagotchi-avg-1h")
    |> range(start: -task.every)
    |> filter(fn: (r) => r._field == "value")
    |> aggregateWindow(every: 1d, fn: mean, createEmpty: false)
    |> to(bucket: "omagotchi-avg-1d")
