#!/usr/bin/env python3
"""Builds the notification service as one executable file, `notifyd.pyz`, with zipapp.

Usage: python3 scripts/build_helper.py [--output PATH]

The archive holds `helper/notifyd/*.py` under `notifyd/` and a small top-level `__main__.py`
that calls `notifyd.__main__.main`; nothing else (no tests, no bytecode). It starts with the
shebang `#!/usr/bin/env python3`.

The build is reproducible: entries are sorted, stored uncompressed (no dependence on the zlib
version) and carry a fixed timestamp and mode, so the same sources give the same bytes.

The default output is `build/notifyd/notifyd.pyz` under the repository, which git ignores. An
output inside the repository but outside its `build/` directory is refused, so the build can
never write into tracked paths. The output path is printed on success.

Exit codes: 0 built, 1 build failed, 2 invalid arguments or refused output path.

Standard library only; Python 3.9 syntax.
"""
import argparse
import io
import os
import sys
import tempfile
import zipapp
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PACKAGE = REPO / "helper" / "notifyd"
BUILD_DIR = REPO / "build"
INTERPRETER = "/usr/bin/env python3"
FIXED_TIME = (1980, 1, 1, 0, 0, 0)
FILE_MODE = 0o644

LAUNCHER = (
    '"""Runs notifyd from the zipapp."""\n'
    "import sys\n"
    "\n"
    "from notifyd.__main__ import main\n"
    "\n"
    'if __name__ == "__main__":\n'
    "    sys.exit(main())\n"
)


class BuildError(Exception):
    pass


def default_output():
    return BUILD_DIR / "notifyd" / "notifyd.pyz"


def _inside(path, parent):
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def check_output(output):
    """Returns the absolute output path; raises BuildError if it could reach a tracked path.

    The path is checked twice. Lexically: inside the repository it must lie under `build/`, and
    neither `build/` nor any directory below it on the way to the output may be a symlink. Then
    resolved: wherever symlinks outside the repository lead, the real target must not land in
    the repository outside the real `build/` directory.
    """
    target = Path(os.path.abspath(str(output)))
    repo = Path(os.path.abspath(str(REPO)))
    build = repo / BUILD_DIR.name
    if _inside(target, repo):
        if not _inside(target.parent, build) and target.parent != build:
            raise BuildError("output must be outside the repository or under its build/ directory")
        directory = build
        directories = [build]
        for part in target.parent.relative_to(build).parts:
            directory = directory / part
            directories.append(directory)
        if any(d.is_symlink() for d in directories):
            raise BuildError("build/ or a directory below it is a symlink")
    resolved = target.parent.resolve() / target.name
    real_repo = repo.resolve()
    real_build = real_repo / BUILD_DIR.name
    if _inside(resolved, real_repo):
        if build.is_symlink() or real_build.is_symlink():
            raise BuildError("build/ is a symlink")
        if not _inside(resolved.parent, real_build) and resolved.parent != real_build:
            raise BuildError("output must be outside the repository or under its build/ directory")
    if resolved.is_dir():
        raise BuildError("output is a directory")
    return resolved


def sources():
    """The package's .py files, sorted, as (archive name, bytes)."""
    entries = []
    for path in sorted(PACKAGE.iterdir(), key=lambda p: p.name):
        if path.suffix != ".py":
            continue
        if path.is_symlink() or not path.is_file():
            raise BuildError("unexpected entry in the package: %s" % path.name)
        entries.append(("notifyd/" + path.name, path.read_bytes()))
    names = [name for name, _ in entries]
    for required in ("notifyd/__init__.py", "notifyd/__main__.py"):
        if required not in names:
            raise BuildError("package is missing %s" % required)
    return entries


def archive_bytes():
    """The zip archive, without a shebang, built deterministically in memory."""
    entries = [("__main__.py", LAUNCHER.encode("utf-8"))] + sources()
    entries.sort(key=lambda entry: entry[0])
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=FIXED_TIME)
            info.compress_type = zipfile.ZIP_STORED
            info.create_system = 3
            info.external_attr = (0o100000 | FILE_MODE) << 16
            archive.writestr(info, data)
    return buffer.getvalue()


def build(output):
    target = check_output(output)
    target.parent.mkdir(parents=True, exist_ok=True)
    data = archive_bytes()
    fd, tmp = tempfile.mkstemp(prefix=".notifyd-", suffix=".tmp", dir=str(target.parent))
    os.close(fd)
    try:
        # zipapp copies an existing archive and writes the shebang in front of it.
        zipapp.create_archive(io.BytesIO(data), target=tmp, interpreter=INTERPRETER)
        os.chmod(tmp, 0o755)
        os.replace(tmp, str(target))
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(prog="build_helper.py", description=__doc__.splitlines()[0])
    parser.add_argument("--output", metavar="PATH", default=None,
                        help="where to write notifyd.pyz (default: build/notifyd/notifyd.pyz)")
    args = parser.parse_args(argv)
    try:
        target = build(args.output if args.output is not None else default_output())
    except BuildError as error:
        print("build_helper: %s" % error, file=sys.stderr)
        return 2
    except OSError as error:
        print("build_helper: build failed (%s)" % type(error).__name__, file=sys.stderr)
        return 1
    print(target)
    return 0


if __name__ == "__main__":
    sys.exit(main())
