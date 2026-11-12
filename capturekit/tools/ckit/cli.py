"""`ckit` command-line entry point (stub)."""

from typing import Annotated

import typer

from ckit import __version__

app = typer.Typer(help="CaptureKit Scan Bundle tools.", no_args_is_help=True)


def _version_callback(value: bool) -> None:
    if value:
        typer.echo(f"ckit {__version__}")
        raise typer.Exit()


@app.callback()
def main(
    version: Annotated[
        bool,
        typer.Option("--version", callback=_version_callback, is_eager=True, help="Show version."),
    ] = False,
) -> None:
    """CaptureKit Scan Bundle tools."""
