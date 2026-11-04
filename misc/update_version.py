import re
import sys
import subprocess

# Files that actually carry the application version. api/build.gradle reads ../VERSION at build
# time and Start.java delegates to BuildInfo, so neither needs rewriting anymore.
VERSION_FILE = "VERSION"
PACKAGE_JSON_FILE = "web/package.json"
PACKAGE_LOCK_FILE = "web/package-lock.json"


def read_version():
    """读取 VERSION 文件中的版本号"""
    with open(VERSION_FILE, "r") as file:
        return file.read().strip()


def write_version(version):
    """将版本号写入 VERSION 文件"""
    with open(VERSION_FILE, "w") as file:
        file.write(version + "\n")


def update_package_json(version):
    """更新 package.json 中的版本号"""
    with open(PACKAGE_JSON_FILE, "r") as file:
        content = file.read()

    updated_content = re.sub(r'"version"\s*:\s*".*?"', f'"version": "{version}"', content, count=1)

    with open(PACKAGE_JSON_FILE, "w") as file:
        file.write(updated_content)


def update_package_lock(version):
    """同步 package-lock.json 根节点的版本号（保留第三方依赖版本不变）"""
    with open(PACKAGE_LOCK_FILE, "r") as file:
        content = file.read()

    updated_content, root_replacements = re.subn(
        r'("name"\s*:\s*"telegram-files-web",\s*"version"\s*:\s*")[^"]+(")',
        rf'\g<1>{version}\g<2>',
        content,
        count=2,
    )
    if root_replacements != 2:
        raise RuntimeError("Could not update both root versions in package-lock.json")

    with open(PACKAGE_LOCK_FILE, "w") as file:
        file.write(updated_content)


def git_commit_and_tag(version, message):
    """执行 Git 提交并创建标签"""
    try:
        subprocess.run(
            ["git", "add", VERSION_FILE, PACKAGE_JSON_FILE, PACKAGE_LOCK_FILE],
            check=True,
        )
        subprocess.run(["git", "commit", "-m", message], check=True)
        subprocess.run(["git", "tag", version], check=True)
        print(f"已成功提交修改并创建标签：{version}")
    except subprocess.CalledProcessError as e:
        print(f"Git 操作失败: {e}")
        sys.exit(1)


def main():
    # 获取命令行参数中的版本号
    version = sys.argv[1] if len(sys.argv) > 1 else None

    if version is None:
        print("请提供版本号，例如：python update_version.py 1.2.3")
        sys.exit(1)

    # 输入变更内容
    change_message = input("请输入版本变更内容: ").strip()

    # 更新 VERSION 文件
    write_version(version)

    # 更新 package.json 与 package-lock.json
    update_package_json(version)
    update_package_lock(version)

    print(f"版本号已更新为：{version}")

    if change_message:
        # 提交到 Git 并创建标签
        git_commit_and_tag(version, change_message)


if __name__ == "__main__":
    main()
