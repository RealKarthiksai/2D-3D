"""`ckit` command-line entry point (stub)."""

from pathlib import Path
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


def _dump(obj) -> None:
    import json

    typer.echo(json.dumps(obj, indent=2, sort_keys=True))


@app.command("inspect-raw")
def inspect_raw_cmd(
    session: Path,
    plot: Annotated[Path | None, typer.Option(help="Write a top-down trajectory PNG.")] = None,
    clock: Annotated[bool, typer.Option(help="Print clock-check offsets only.")] = False,
) -> None:
    """Summarize a raw recorder session (rates, drops, tracking) and plot the trajectory."""
    from ckit.inspect_raw import clock_offsets, summarize
    from ckit.inspect_raw import plot as do_plot
    from ckit.raw import RawSession

    s = RawSession.load(session)
    if clock:
        _dump(clock_offsets(s))
        return
    _dump(summarize(s))
    if plot:
        do_plot(s, plot)


@app.command("synth")
def synth_cmd(
    scenario: str,
    out: Path,
    seed: int = 0,
    duration: Annotated[float | None, typer.Option(help="Cap duration in seconds.")] = None,
    frame_hz: float = 5.0,
) -> None:
    """Render a synthetic golden-scenario raw session (G1..G8), see ADR 0002."""
    from ckit.synth.scenarios import generate

    generate(scenario, out, seed=seed, duration=duration, frame_hz=frame_hz)
    typer.echo(str(out))


@app.command("import-raw")
def import_raw_cmd(raw: Path, out: Path) -> None:
    """Convert a Phase 0 raw session into a Scan Bundle (all frames kept as keyframes)."""
    from ckit.bundle.import_raw import import_raw

    counts = import_raw(raw, out)
    _dump(counts)


@app.command("validate")
def validate_cmd(
    bundle: Path,
    level: Annotated[str, typer.Option(help="structural|integrity|semantic|quality")] = "semantic",
    json_out: Annotated[Path | None, typer.Option("--json", help="Write JSON report here.")] = None,
) -> None:
    """Validate a Scan Bundle. Exit code 1 on any ERROR."""
    import json

    from ckit.bundle.validate import validate

    rep = validate(bundle, level)
    if json_out:
        json_out.write_text(json.dumps(rep.to_json(), indent=2, sort_keys=True) + "\n")
    typer.echo(rep.summary())
    raise typer.Exit(1 if rep.verdict == "FAIL" else 0)
