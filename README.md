# jenkins-atom-python-env

Python 环境 — 对应 `python` 插件里"准备解释器"那一半，作为原子。

demo 流水线暴露的缺口：`python-build` 只会打 wheel，没有东西负责建 venv 与装依赖。这个原子建 `.venv`、按 `requirements.txt` 装依赖、在虚拟环境里跑一条命令，并把 `VIRTUAL_ENV` 与 `PATH` 作为同名出参交给后续步骤。

## Build and smoke test

```bash
./build.sh
./tools/smoke.sh
```
