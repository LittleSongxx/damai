# ©️版权告示
为了保障星球用户权益，大麦后台管理系统 damai-pro-front-manage 不再实行开源策略，而是通过邀请星球用户进入私有项目进行学习。
严禁未经本项目原作者明确书面授权擅自分享至 GitHub、Gitee 等任何开放平台。违者将面临版权法律追究。

- **知识星球:** [《侵权责任法》、《著作权法》和《信息网络传播权保护条例》。](https://support.zsxq.com/guidance.html)
- **项目版权:**[《中华人民共和国著作权法实施条例》。](https://gitcode.com/java_up/introduce/blob/main/copyright_%E4%B8%AD%E5%8D%8E%E4%BA%BA%E6%B0%91%E5%85%B1%E5%92%8C%E5%9B%BD%E8%91%97%E4%BD%9C%E6%9D%83%E6%B3%95%E5%AE%9E%E6%96%BD%E6%9D%A1%E4%BE%8B.pdf)

# 申请大麦pro后台管理项目
大麦pro后台管理项目不再开源，采取闭源，放到了本人的私有仓库中，私有仓库地址：https://gitcode.com/java_up

进入私有仓库后，会看到 damai-pro-front-manage 项目，这个就是大麦pro后台管理的前端项目了

如果私有仓库进不去或者提示没有权限的话，需要申请权限！

如果加入星球的小伙伴按照以下指引申请开通权限：https://articles.zsxq.com/id_dbr2hgl0bx4d.html

**在启动此前端项目之前，请先确保大麦pro后端服务已运行。如果后端尚未启动，请先完成大麦pro后端项目的启动流程。** 

# 安装node.js
damai-pro-front-manage 是基于 vue-vben-admin 改造而来，所以对 node.js 的版本要求较新，需要 **20.15.0** 及以上版本

访问 Nodejs 下载页面：https://nodejs.org/download/release/v20.15.0

根据 Windows 或者 Mac 系统下载安装不同的安装包。下载完成后，双击打开对应的安装软件包，一路点击就可以完成安装了

- **Windows 系统**：node-v20.15.0-win-x86.zip

- **Mac 系统**：node-v20.15.0.pkg

安装完成后，终端输入 node -v 检查是否安装成功，当出现 node 的版本信息后说明安装成功了

# 电脑已经安装了node.js
如果电脑中已经存在 Nodejs 环境，检查版本是否在 20.15.0 版本以上，如果不是的话。需要安装 Nodejs 多版本控制组件，下载多个 Nodejs 共存，并通过命令切换。或者直接删除之前版本，下载 20.15.0 及以上版本就可以了

## 安装多个版本node：
https://blog.csdn.net/qq_38405436/article/details/132279098

# 启动大麦pro后台管理项目
## 安装依赖
将 damai-pro-front-manage 项目下载下来后，进入 damai-pro-front-manage 的根目录，执行安装依赖的命令：
```shell
# 使用项目指定的pnpm版本进行依赖安装
npm i -g corepack

# 安装依赖
pnpm install
```

## 启动项目
```shell
# 启动项目
pnpm dev
```

## 访问项目
输入访问地址即可：http://localhost:5878/
