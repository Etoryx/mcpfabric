/**
 * Background jobs: multi-step macro actions (travel, explore, collect, craft) that run inside the
 * runtime instead of the model's tool loop. The model starts one, keeps thinking or waits on
 * `job_status`, and gets a summary at the end — the tick-level feedback loop stays out of the LLM.
 *
 * One job runs at a time because there is one body; starting another requires cancelling first.
 * A job belongs to the MCP session that started it: only that session sees and cancels it, and
 * closing the session cancels it. Every job also stops at an overall time limit.
 */

export type JobState = "running" | "done" | "failed" | "cancelled";

export interface Job {
  id: number;
  kind: string;
  /** One-line description of what was asked. */
  summary: string;
  state: JobState;
  startedAt: number;
  endedAt?: number;
  progress: string;
  log: string[];
  result?: string;
  /** Id of the MCP session that started the job; undefined = visible to every session. */
  owner?: string;
}

/** Thrown inside a job to stop it with a readable reason. */
export class JobFailure extends Error {}

export class JobCancelled extends Error {
  constructor() {
    super("cancelled");
  }
}

export interface JobContext {
  readonly signal: AbortSignal;
  log(message: string): void;
  progress(message: string): void;
  /** Abortable sleep; throws {@link JobCancelled} when the job is cancelled. */
  sleep(ms: number): Promise<void>;
  /** Throws {@link JobCancelled} if the job was cancelled. */
  checkpoint(): void;
}

const LOG_LINES = 40;
const HISTORY = 10;
/** Overall cap on one job; collect_blocks' own budget (up to 30 min) ends just before it. */
export const JOB_MAX_MS = 31 * 60_000;

/** Can a session with this id see the job? */
const visibleTo = (job: Job, owner: string | undefined) => job.owner === undefined || owner === undefined || job.owner === owner;

export class JobManager {
  private nextId = 1;
  private current?: { job: Job; abort: AbortController; done: Promise<void> };
  private readonly history: Job[] = [];

  constructor(
    /** Called once when a job ends (not when cancelled): record it in episodic memory, etc. */
    private readonly onFinish: (job: Job) => void = () => {},
    /** Called when a job stops for any reason, to release the body (stop navigation, ...). */
    private readonly onStop: () => Promise<void> = async () => {},
    private readonly maxMs: number = JOB_MAX_MS,
  ) {}

  /** The running job, whoever started it. */
  running(): Job | undefined {
    return this.current?.job;
  }

  start(kind: string, summary: string, run: (ctx: JobContext) => Promise<string>, owner?: string): Job {
    if (this.current) {
      const cur = this.current.job;
      throw new Error(
        visibleTo(cur, owner)
          ? `Job #${cur.id} (${cur.kind}) is still running; job_cancel it first.`
          : "Another MCP session is running a job; wait for it to finish.",
      );
    }
    const job: Job = { id: this.nextId++, kind, summary, state: "running", startedAt: Date.now(), progress: "starting", log: [] };
    if (owner !== undefined) job.owner = owner;
    const abort = new AbortController();
    let timedOut = false;
    const limit = setTimeout(() => {
      timedOut = true;
      abort.abort();
    }, this.maxMs);
    limit.unref?.();
    const ctx: JobContext = {
      signal: abort.signal,
      log: (message) => {
        job.log.push(message);
        if (job.log.length > LOG_LINES) job.log.splice(0, job.log.length - LOG_LINES);
      },
      progress: (message) => {
        job.progress = message;
      },
      sleep: (ms) =>
        new Promise<void>((resolve, reject) => {
          if (abort.signal.aborted) return reject(new JobCancelled());
          const timer = setTimeout(() => {
            abort.signal.removeEventListener("abort", onAbort);
            resolve();
          }, ms);
          const onAbort = () => {
            clearTimeout(timer);
            reject(new JobCancelled());
          };
          abort.signal.addEventListener("abort", onAbort, { once: true });
        }),
      checkpoint: () => {
        if (abort.signal.aborted) throw new JobCancelled();
      },
    };

    const done = (async () => {
      try {
        job.result = await run(ctx);
        job.state = abort.signal.aborted ? "cancelled" : "done";
      } catch (err) {
        if (err instanceof JobCancelled || abort.signal.aborted) {
          job.state = "cancelled";
          job.result ??= "cancelled";
        } else {
          job.state = "failed";
          job.result = err instanceof Error ? err.message : String(err);
        }
      } finally {
        clearTimeout(limit);
        if (timedOut) {
          // A failure, not a cancel: it is recorded in memory so later plans can account for it.
          job.state = "failed";
          job.result = `stopped at the job time limit (${Math.round(this.maxMs / 1000)} s)`;
        }
        job.endedAt = Date.now();
        this.current = undefined;
        this.history.unshift(job);
        if (this.history.length > HISTORY) this.history.pop();
        try {
          await this.onStop();
        } catch {
          // releasing the body is best effort
        }
        if (job.state !== "cancelled") {
          try {
            this.onFinish(job);
          } catch {
            // recording the outcome must never break the job manager
          }
        }
      }
    })();
    this.current = { job, abort, done };
    return job;
  }

  /** A job by id, or the running/last one; with `owner`, only jobs that session may see. */
  get(id?: number, owner?: string): Job | undefined {
    const cur = this.current?.job;
    if (id === undefined) {
      if (cur && visibleTo(cur, owner)) return cur;
      return this.history.find((j) => visibleTo(j, owner));
    }
    const job = cur?.id === id ? cur : this.history.find((j) => j.id === id);
    return job && visibleTo(job, owner) ? job : undefined;
  }

  /** Cancel the running job (optionally only if it has this id / this owner); resolves once it has stopped. */
  async cancel(id?: number, owner?: string): Promise<Job | undefined> {
    const cur = this.current;
    if (!cur || (id !== undefined && cur.job.id !== id) || !visibleTo(cur.job, owner)) return undefined;
    cur.abort.abort();
    await cur.done;
    return cur.job;
  }

  /** Cancel the running job if `owner` started it (its MCP session closed). */
  async cancelOwnedBy(owner: string): Promise<Job | undefined> {
    return this.current?.job.owner === owner ? this.cancel(undefined, owner) : undefined;
  }

  /** Wait until the job ends or `timeoutMs` passes, whichever is first. */
  async wait(id: number | undefined, timeoutMs: number, owner?: string): Promise<Job | undefined> {
    const cur = this.current;
    if (cur && (id === undefined || cur.job.id === id) && visibleTo(cur.job, owner) && timeoutMs > 0) {
      let timer: NodeJS.Timeout | undefined;
      await Promise.race([cur.done, new Promise<void>((r) => (timer = setTimeout(r, timeoutMs)))]);
      clearTimeout(timer);
    }
    return this.get(id, owner);
  }
}

export function formatJob(job: Job, now: number = Date.now()): string {
  const secs = Math.round(((job.endedAt ?? now) - job.startedAt) / 1000);
  const lines = [`job #${job.id} ${job.kind} [${job.state}] ${secs}s — ${job.summary}`];
  if (job.state === "running") lines.push(`progress: ${job.progress}`);
  if (job.result) lines.push(`result: ${job.result}`);
  if (job.log.length > 0) lines.push("log:", ...job.log.slice(-8).map((l) => `  ${l}`));
  return lines.join("\n");
}
