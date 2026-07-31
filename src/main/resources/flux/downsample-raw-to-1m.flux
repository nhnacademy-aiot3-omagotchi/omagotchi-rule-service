// raw → 1분 평균
// createTaskEvery(name, flux, "1m", orgId)로 등록하면 option task 블록이 자동으로 붙는다.
// (그래서 여기 -task.every 는 "1분"으로 해석된다)
from(bucket: "omagotchi-raw")
    |> range(start: -task.every)
    |> filter(fn: (r) => r._field == "value")
    |> aggregateWindow(every: 1m, fn: mean, createEmpty: false)
    |> to(bucket: "omagotchi-avg-1m")
