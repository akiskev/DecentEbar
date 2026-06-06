# shotlogs/

Drop exported shot logs here (the `.html` files the app produces — they contain an
embedded `shotlog-data` JSON block). Then run the Phase 0 analysis:

```
python tools/puck_probe_analysis.py
python tools/puck_probe_analysis.py --csv runs.csv   # also dump a CSV to track over time
```

See [../docs/puck-resistance-feedforward.md](../docs/puck-resistance-feedforward.md)
for what the numbers mean and the exit criteria (clean-shot `alpha` CoV < ~25%).

Tip: for each shot, jot down the grind setting / dose / coffee so you can correlate
later — `t_fd` (time to first drop) is confounded by dose and distribution, not just grind.

Logs older than the export feature (commit 605351e) have no embedded data and are skipped.
