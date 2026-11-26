# Core and LiFi commits

Treat `.private/lifi` as an independent repository. A request to commit or
push DeadlinerCore covers Core only. Leave existing LiFi edits with their
owner and report them if the Core change depends on them.

When the user explicitly includes LiFi in a coordinated change, review its
diff separately. Push the intended LiFi commit to the private remote first,
then update `lifi.revision` in Core to that exact SHA. Never force-add the
private checkout to the Core index.

## 客户端与发布交接

有包读取权限的协作者首次运行 `gh auth login -h github.com -s read:packages` 后，客户端即可按 `core-package.lock.json` 下载预编译 Core 并在本地构建。
升级依赖时，把发布命令输出的 `version` 和 `manifest` 一起写入锁文件。当前已验证的基线是 [0.2.0-preview.20261126.3](https://github.com/orgs/DeadlinerStudio/packages/container/deadliner-core/1641726630)。
发布新版时，在本地备齐七件产物，先运行 `python3 runscript/publishCorePackage.py --check <release-dir>`，校验通过后去掉 `--check` 上传。[本地构建与发布步骤](README.md#build-locally-and-publish-to-github-packages)
