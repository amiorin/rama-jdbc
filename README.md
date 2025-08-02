# Intro

`rama-jdbc` is a connector written in `clojure` to implement real-time analytics with any SQL database. Instead of the mainstream approach with SQL databases, CDC, Kafka, and Flink, you can use the outbox pattern with any SQL database and [rama](https://redplanetlabs.com/) together with this connector.

## YouTube demo
[![Watch the video](https://img.youtube.com/vi/uMkd6YxrmIA/hqdefault.jpg)](https://www.youtube.com/watch?v=uMkd6YxrmIA)

## CI
There is parity between dev and CI. The same container is used for development and for CI. To reproduce an error in CI, you can extract the docker command used in CI and run it in development.

``` sh
cat .github/workflows/ci.yml | jet -i yaml -o json | jq -r .jobs.ci.steps[1].run | bash -s
```
