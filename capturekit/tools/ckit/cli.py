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
def import_raw_cmd(
    raw: Path,
    out: Path,
    select_keyframes: Annotated[
        bool, typer.Option(help="Keep only frames the core keyframe selector picks (Phase 4).")
    ] = False,
    config: Annotated[Path | None, typer.Option(help="Flat JSON quality config overrides.")] = None,
) -> None:
    """Convert a raw session into a Scan Bundle (all frames kept unless --select-keyframes)."""
    import json

    from ckit.bundle.import_raw import import_raw, import_raw_selected

    if select_keyframes:
        cfg = json.loads(config.read_text()) if config else None
        _dump(import_raw_selected(raw, out, cfg))
    else:
        _dump(import_raw(raw, out))


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


@app.command("export-colmap")
def export_colmap_cmd(
    bundle: Path,
    out: Path,
    max_gap_ms: Annotated[float, typer.Option(help="Max pose bracket for interpolation.")] = 100.0,
) -> None:
    """Write a COLMAP text model (cameras, images as cam_from_world wxyz, empty points)."""
    from ckit.convert.colmap import export_colmap

    _dump(export_colmap(bundle, out, int(max_gap_ms * 1e6)))


@app.command("reprojection-report")
def reprojection_report_cmd(
    bundles: list[Path],
    json_out: Annotated[Path | None, typer.Option("--json", help="Write JSON report here.")] = None,
) -> None:
    """Triangulate each bundle with its own poses (pycolmap) and report reprojection error."""
    import json

    from ckit.convert.reprojection import report, table

    rep = report(bundles)
    if json_out:
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(json.dumps(rep, indent=2, sort_keys=True) + "\n")
    typer.echo(table(rep))
    typer.echo(json.dumps(rep["summary"], sort_keys=True))


@app.command("replay")
def replay_cmd(
    bundle: Path,
    sink: Annotated[list[str] | None, typer.Option(help="Core sink name (repeatable).")] = None,
    out: Annotated[Path | None, typer.Option(help="Write report JSON here (else stdout).")] = None,
    fast: Annotated[
        bool, typer.Option("--fast/--realtime", help="Fast (default) or realtime.")
    ] = True,
    speed: Annotated[float, typer.Option(help="Realtime speed factor.")] = 1.0,
    seed: int = 0,
    drop_frames: Annotated[float, typer.Option(help="Drop probability per frame.")] = 0.0,
    jitter_ms: Annotated[float, typer.Option(help="+- jitter on frame/IMU t_ns.")] = 0.0,
    inject_tracking_loss: Annotated[
        list[str] | None, typer.Option(help="T0:T1 seconds from start (repeatable).")
    ] = None,
    inject_thermal: Annotated[
        list[str] | None, typer.Option(help="T:STATE nominal|fair|serious|critical (repeatable).")
    ] = None,
    config: Annotated[
        Path | None, typer.Option(help="JSON {sink: {key: value}} overrides.")
    ] = None,
) -> None:
    """Replay a bundle through core sinks; prints a deterministic JSON report."""
    import json

    from ckit.replay.driver import Faults, dumps, replay

    f = Faults(
        drop_frames=drop_frames,
        jitter_ms=jitter_ms,
        tracking_loss=[Faults.parse_window(w) for w in inject_tracking_loss or []],
        thermal=[Faults.parse_thermal(t) for t in inject_thermal or []],
        seed=seed,
    )
    cfg = json.loads(config.read_text()) if config else None
    text = dumps(replay(bundle, sink, f, realtime=not fast, speed=speed, sink_config=cfg))
    if out:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text)
    else:
        typer.echo(text, nl=False)


@app.command("replay-compare")
def replay_compare_cmd(
    a: Path,
    b: Path,
    abs_tol: Annotated[float, typer.Option("--abs")] = 1e-9,
    rel_tol: Annotated[float, typer.Option("--rel")] = 1e-6,
    tol: Annotated[list[str] | None, typer.Option(help="KEY=ABS per-key tolerance.")] = None,
) -> None:
    """Compare two replay reports. Exit 1 if they differ beyond tolerance."""
    import json

    from ckit.replay.compare import compare

    kt = {k: float(v) for k, v in (t.split("=", 1) for t in tol or [])}
    diffs = compare(json.loads(a.read_text()), json.loads(b.read_text()), abs_tol, rel_tol, kt)
    for d in diffs:
        typer.echo(d)
    typer.echo(f"{len(diffs)} difference(s)")
    raise typer.Exit(1 if diffs else 0)


@app.command("replay-plot")
def replay_plot_cmd(bundle: Path, out: Path) -> None:
    """Top-down trajectory PNG coloured by tracking state."""
    from ckit.replay.plot import plot

    typer.echo(str(plot(bundle, out)))


@app.command("quality-report")
def quality_report_cmd(
    bundles: list[Path],
    json_out: Annotated[Path | None, typer.Option("--json", help="Write JSON report here.")] = None,
    no_sfm: Annotated[bool, typer.Option("--no-sfm", help="Skip pycolmap registration.")] = False,
    uniform: Annotated[
        bool, typer.Option(help="Also register the same count picked at a fixed interval.")
    ] = False,
    config: Annotated[Path | None, typer.Option(help="Flat JSON quality config overrides.")] = None,
) -> None:
    """Keyframe density, rejection histogram, pycolmap registration rate of selected keyframes."""
    import json

    from ckit.quality_report import quality_report

    cfg = json.loads(config.read_text()) if config else None
    rows = [quality_report(b, cfg, register=not no_sfm, uniform=uniform) for b in bundles]
    if json_out:
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(json.dumps({"bundles": rows}, indent=2, sort_keys=True) + "\n")
    for r in rows:
        sfm = r.get("sfm", {})
        typer.echo(
            f"{r['bundle']:<16} frames={r['frames']:>4} kf={r['keyframes']:>4} "
            f"kf/m={r['keyframes_per_m']:<7} rejected={r['hard_rejected']:>4} "
            f"reg={sfm.get('registration_rate', '-')} "
            f"reg_uniform={r.get('sfm_uniform_same_count', {}).get('registration_rate', '-')} "
            f"hard={r['hard_reject_histogram']}"
        )


@app.command("simulate-capture")
def simulate_capture_cmd(
    source: Path,
    out: Path,
    threaded: Annotated[bool, typer.Option(help="Use the core worker thread.")] = False,
    kill_after: Annotated[
        int | None, typer.Option(help="SIGKILL this process after N input events (crash test).")
    ] = None,
    config: Annotated[Path | None, typer.Option(help="Flat JSON session config.")] = None,
) -> None:
    """Replay a bundle through the real C API like a phone would; --kill-after simulates a crash."""
    import json

    from ckit.simulate import simulate

    cfg = json.loads(config.read_text()) if config else None
    _dump(simulate(source, out, cfg, threaded, kill_after))


@app.command("recover")
def recover_cmd(bundle: Path) -> None:
    """Turn an interrupted session directory into a valid partial bundle (ck_recover)."""
    from ckit.capi import recover

    st, rep = recover(bundle)
    _dump(rep)
    raise typer.Exit(0 if st == 0 else 1)


@app.command("verdict")
def verdict_cmd(bundle: Path, level: str = "semantic") -> None:
    """Validate a bundle and print the user-facing verdict (plain-language messages and actions)."""
    from ckit.bundle.validate import validate
    from ckit.verdict import for_report

    rep = validate(bundle, level)
    _dump(for_report(rep.to_json()["issues"]))


@app.command("verdict-docs")
def verdict_docs_cmd(out: Path | None = None) -> None:
    """Write docs/verdict-messages.md from the verdict table."""
    from ckit.verdict import to_markdown

    text = to_markdown()
    if out:
        out.write_text(text)
    else:
        typer.echo(text, nl=False)


@app.command("upload")
def upload_cmd(
    bundle: Path,
    server: Annotated[str, typer.Option(help="tus endpoint")] = "http://localhost:1080/files/",
    verifier: Annotated[
        str, typer.Option(help="verifier base URL ('' = do not wait)")
    ] = "http://localhost:8081",
    delete_after_verify: Annotated[
        bool, typer.Option(help="Delete local files after COMPLETE (needs --consent).")
    ] = False,
    consent: Annotated[
        bool, typer.Option(help="User consented to upload and local deletion.")
    ] = False,
) -> None:
    """Upload a bundle via tus (manifest last), resume after kill, wait for the server verdict."""
    from ckit.upload import TusUploader
    from ckit.upload import delete_after_verify as retention

    if not consent:
        typer.echo("refusing to upload without --consent (consent flag is recorded by the app)")
        raise typer.Exit(2)
    res = TusUploader(server, verifier or None).upload_bundle(bundle)
    deleted = retention(bundle, res, delete_after_verify) if delete_after_verify else False
    _dump({"status": res.status, "reason": res.reason, "repaired": res.repaired,
           "retries": res.retries, "deleted_local": deleted})  # fmt: skip
    raise typer.Exit(0 if res.status in ("COMPLETE", "UPLOADED") else 1)


@app.command("guide-eval")
def guide_eval_cmd(
    bundles: Annotated[
        list[Path] | None, typer.Argument(help="Replay these bundles instead.")
    ] = None,
    rooms: Annotated[str, typer.Option(help="comma list: box,L,furniture")] = "box,L,furniture",
    policies: Annotated[
        str, typer.Option(help="comma list: planner,scripted,wander")
    ] = "planner,scripted,wander",
    seeds: int = 3,
    max_s: Annotated[float, typer.Option(help="Simulated seconds per run.")] = 450.0,
    params: Annotated[str, typer.Option(help="Flat JSON GuideParams overrides.")] = "{}",
    compliance: float = 0.85,
    workers: int = 6,
    json_out: Annotated[Path | None, typer.Option("--json", help="Write JSON report here.")] = None,
) -> None:
    """Does guidance help? Simulated walkers (planner/scripted/wander) or guidance on bundles."""
    import json

    from ckit.guide_eval import replay_bundle, simulate_matrix, table

    p = json.loads(params)
    if bundles:
        rows = [replay_bundle(b, p) for b in bundles]
        rep = {"bundles": rows}
        for r in rows:
            typer.echo(
                f"{r['bundle']:<16} tier={r['tier']} coverage={r['coverage_completeness']} "
                f"instructions={r['instructions']} repetition={r['repetition_rate']} "
                f"unsafe={r['unsafe_targets']}"
            )
    else:
        rep = simulate_matrix(
            tuple(rooms.split(",")),
            tuple(policies.split(",")),
            seeds,
            max_s,
            p,
            compliance,
            workers=workers,
        )
        typer.echo(table(rep))
    if json_out:
        json_out.parent.mkdir(parents=True, exist_ok=True)
        json_out.write_text(json.dumps(rep, indent=2, sort_keys=True) + "\n")
