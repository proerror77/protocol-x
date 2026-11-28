"""Player-level reception progress, with a compatible projection for existing work items."""


def records(plan, task, stages):
    stored = plan.get("player_progress")
    legacy = {stage["id"]: {"confirmed_at": step.get("confirmed_at"), "source": "legacy_group"}
              for stage, step in zip(stages, task["service_facts"]) if step["status"] == "done"}
    return {player["id"]: ({"steps": dict(legacy)} if stored is None else stored.get(player["id"], {"steps": {}}))
            for player in plan["players"]}


def next_stage(record, stages):
    return next((stage for stage in stages if stage["id"] not in record["steps"]), None)


def allowed_stage(record, stages, plan, task, user):
    step = next_stage(record, stages)
    if step is None or (step.get("requires_hotel_confirmation") and not plan["hotel"]["confirmed"]):
        return None
    responsible = plan["greeter"] if step["responsible"] == "greeter" else task["assigned_driver"]
    return step["id"] if user["role"] == "dispatcher" or responsible == user["actor"] else None


def project(plan, task, stages, user):
    progress = records(plan, task, stages)
    return [
        {"id": player["id"], "name": player["name"], "completed": [stage["id"] for stage in stages if stage["id"] in record["steps"]],
         "state": next((stage["id"] for stage in reversed(stages) if stage["id"] in record["steps"]), "waiting_meet"),
         "next_stage": allowed_stage(record, stages, plan, task, user),
         "exception": record.get("exception")}
        for player in plan["players"] for record in [progress[player["id"]]]
    ]


def started(task, stage, index):
    return stage in task.get("arrival_started_stages", ()) or task["service_facts"][index]["status"] == "done"


def aggregate(task, progress, stages):
    task["arrival_started_stages"] = [
        stage["id"] for stage in stages if any(stage["id"] in record["steps"] for record in progress.values())
    ]
    pending_seen = False
    for stage, step in zip(stages, task["service_facts"]):
        marks = [record["steps"].get(stage["id"]) for record in progress.values()]
        if all(mark is not None for mark in marks):
            step["status"] = "done"
            stamps = [mark["confirmed_at"] for mark in marks if mark.get("confirmed_at")]
            if stamps:
                step["confirmed_at"] = max(stamps)
            continue
        step["status"] = "pending" if pending_seen else "current"
        step.pop("confirmed_at", None)
        pending_seen = True
