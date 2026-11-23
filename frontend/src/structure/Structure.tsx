import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
    ArrowDown,
    ArrowUp,
    Check,
    ClipboardPaste,
    Copy,
    Lock,
    Pencil,
    Plus,
    Repeat,
    Shuffle,
    Trash2,
    X,
} from "lucide-react";
import {
    Fragment,
    useState,
    useSyncExternalStore,
    type FormEvent,
} from "react";
import { Link, useSearchParams } from "react-router";
import { api, unwrap, type Schemas } from "../api/client";
import {
    useEnumerations,
    useObjectSearch,
    useTypes,
    type VersionedObject,
} from "../api/queries";
import { pickLabel } from "../components/AttributeForm";
import {
    ErrorBanner,
    Loading,
    Panel,
    StateBadge,
    TypeIcon,
} from "../components/common";
import { locale, t, tn } from "../i18n/i18n";
import { formatDesignators } from "./designators";
import { FlattenedView, RollupView } from "./Rollup";
import { SubstitutesEditor } from "./Replacements";
import {
    SpecPicker,
    StructureTree,
    useDefaultSpec,
    type Spec,
} from "./StructureTree";
import { ImportStructureButton } from "./StructureImport";
import { ReviseStructureButton } from "./StructureOperations";
import { BulkProgress } from "../pages/Bulk";
import { StepExportButton } from "../standards/Standards";

// Product structure (roadmap 4.1, 4.4): the parts a part uses, edited on its working copy.

type UsageLine = Schemas["UsageLine"];
const PART_TYPE = "00000000-0000-7000-8000-000000000102";

export const useUsages = (objectId: string, workingCopy: boolean) =>
    useQuery({
        queryKey: ["usages", objectId, workingCopy],
        queryFn: () =>
            unwrap(
                api.GET("/api/v1/objects/{id}/usages", {
                    params: { path: { id: objectId }, query: { workingCopy } },
                }),
            ),
    });

/** The `unitOfUse` value list: keys and labels. */
export function useUnits(): { key: string; label: string }[] {
    const { data } = useEnumerations();
    const list = data && [...data.values()].find((e) => e.name === "unitOfUse");
    return (list?.values ?? [])
        .filter((v) => v.active)
        .map((v) => ({ key: v.key, label: pickLabel(v.labels, v.key) }));
}

const qty = (n: number) =>
    n.toLocaleString(locale(), { maximumFractionDigits: 6 });

// ---------- Copied lines (roadmap 4.4), kept in this browser ----------

type Copied = { ids: string[]; from: string };
const KEY = "plm.structure.copied";
const listeners = new Set<() => void>();
let cached: Copied | null | undefined;
function readCopied(): Copied | null {
    if (cached === undefined) {
        try {
            cached = JSON.parse(
                localStorage.getItem(KEY) ?? "null",
            ) as Copied | null;
        } catch {
            cached = null;
        }
    }
    return cached;
}
export function copyLines(c: Copied | null) {
    cached = c;
    try {
        if (c) localStorage.setItem(KEY, JSON.stringify(c));
        else localStorage.removeItem(KEY);
    } catch {
        // Kept for this session only.
    }
    listeners.forEach((l) => l());
}
export function useCopiedLines(): Copied | null {
    return useSyncExternalStore((l) => {
        listeners.add(l);
        return () => listeners.delete(l);
    }, readCopied);
}

/**
 * The Structure tab of a part: the multi-level tree under a configuration specification, or, for
 * whoever has it checked out, the working copy's lines to edit.
 */
export function StructureTab({
    obj,
    editable,
}: {
    obj: VersionedObject;
    editable: boolean;
}) {
    const preferred = useDefaultSpec(obj.contextId);
    const [params] = useSearchParams();
    const fromBaseline = params.get("baseline");
    const [chosen, setChosen] = useState<Spec | null>(
        fromBaseline ? { type: "BASELINE", baselineId: fromBaseline } : null,
    );
    const [view, setView] = useState<"tree" | "flat" | "rollup">("tree");
    const [importJob, setImportJob] = useState<string | null>(null);
    if (editable) return <EditableStructure obj={obj} />;
    const spec = chosen ?? preferred;
    return (
        <Panel
            title={t("Structure")}
            actions={
                <>
                    <span
                        className="segmented"
                        role="tablist"
                        aria-label={t("View")}
                    >
                        {(
                            [
                                ["tree", t("Tree")],
                                ["flat", t("Flattened")],
                                ["rollup", t("Roll-up")],
                            ] as const
                        ).map(([k, l]) => (
                            <button
                                key={k}
                                role="tab"
                                aria-selected={view === k}
                                className={view === k ? "on" : undefined}
                                onClick={() => setView(k)}
                            >
                                {l}
                            </button>
                        ))}
                    </span>
                    <SpecPicker
                        spec={spec}
                        onChange={setChosen}
                        partId={obj.id}
                    />
                    <ReviseStructureButton obj={obj} onStarted={setImportJob} />
                    <ImportStructureButton obj={obj} onStarted={setImportJob} />
                    <StepExportButton
                        objectId={obj.id}
                        released={spec.type === "STATE"}
                    />
                </>
            }
            flush
        >
            {importJob && (
                <div className="import-progress">
                    <BulkProgress
                        jobId={importJob}
                        onClose={() => setImportJob(null)}
                    />
                </div>
            )}
            {view === "tree" && (
                <StructureTree
                    key={JSON.stringify(spec)}
                    obj={obj}
                    spec={spec}
                />
            )}
            {view === "flat" && <FlattenedView obj={obj} spec={spec} />}
            {view === "rollup" && <RollupView obj={obj} spec={spec} />}
        </Panel>
    );
}

function EditableStructure({ obj }: { obj: VersionedObject }) {
    const qc = useQueryClient();
    const { data, error, isLoading } = useUsages(obj.id, true);
    const units = useUnits();
    const copied = useCopiedLines();
    const [editing, setEditing] = useState<string | null>(null);
    const [replacing, setReplacing] = useState<string | null>(null);
    const [substituting, setSubstituting] = useState<string | null>(null);
    const [selected, setSelected] = useState<Set<string>>(new Set());
    const refresh = () =>
        void qc.invalidateQueries({ queryKey: ["usages", obj.id] });
    const removeLine = useMutation({
        mutationFn: (usageId: string) =>
            unwrap(
                api.DELETE("/api/v1/objects/{id}/usages/{usageId}", {
                    params: { path: { id: obj.id, usageId } },
                }),
            ),
        onSuccess: refresh,
    });
    const reorder = useMutation({
        mutationFn: (usageIds: string[]) =>
            unwrap(
                api.PUT("/api/v1/objects/{id}/usages/order", {
                    params: { path: { id: obj.id } },
                    body: { usageIds },
                }),
            ),
        onSuccess: refresh,
    });
    const paste = useMutation({
        mutationFn: (usageIds: string[]) =>
            unwrap(
                api.POST("/api/v1/objects/{id}/usages/paste", {
                    params: { path: { id: obj.id } },
                    body: { usageIds },
                }),
            ),
        onSuccess: refresh,
    });
    const move = (i: number, by: number) => {
        if (!data) return;
        const ids = data.map((l) => l.id);
        const [id] = ids.splice(i, 1);
        ids.splice(i + by, 0, id!);
        reorder.mutate(ids);
    };
    const unitLabel = (key: string) =>
        units.find((u) => u.key === key)?.label ?? key;
    const toggle = (id: string) => {
        const next = new Set(selected);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        setSelected(next);
    };

    return (
        <Panel
            title={t("Structure")}
            note={t("Your working copy: changes become the part's at check-in")}
            actions={
                <>
                    {selected.size > 0 && (
                        <button
                            className="btn small"
                            onClick={() => (
                                copyLines({
                                    ids: [...selected],
                                    from: obj.number,
                                }),
                                setSelected(new Set())
                            )}
                        >
                            <Copy size={12} aria-hidden />{" "}
                            {tn(
                                "Copy {n} line",
                                "Copy {n} lines",
                                selected.size,
                            )}
                        </button>
                    )}
                    {copied && copied.ids.length > 0 && (
                        <button
                            className="btn small"
                            onClick={() => paste.mutate(copied.ids)}
                            disabled={paste.isPending}
                        >
                            <ClipboardPaste size={12} aria-hidden />{" "}
                            {tn(
                                "Paste {n} line from {from}",
                                "Paste {n} lines from {from}",
                                copied.ids.length,
                                { from: copied.from },
                            )}
                        </button>
                    )}
                </>
            }
            flush
        >
            <ErrorBanner
                error={
                    error ?? removeLine.error ?? reorder.error ?? paste.error
                }
            />
            {isLoading ? (
                <Loading />
            ) : (
                <div className="grid-wrap">
                    <table
                        className="grid structure"
                        aria-label={t("Structure")}
                    >
                        <thead>
                            <tr>
                                <th className="check-cell">
                                    <input
                                        type="checkbox"
                                        aria-label={t("Select all lines")}
                                        checked={
                                            !!data?.length &&
                                            selected.size === data.length
                                        }
                                        onChange={() =>
                                            setSelected(
                                                selected.size === data?.length
                                                    ? new Set()
                                                    : new Set(
                                                          data?.map(
                                                              (l) => l.id,
                                                          ),
                                                      ),
                                            )
                                        }
                                    />
                                </th>
                                <th className="num">{t("Line")}</th>
                                <th>{t("Find no.")}</th>
                                <th className="icon-cell" />
                                <th>{t("Number")}</th>
                                <th>{t("Name")}</th>
                                <th>{t("Version")}</th>
                                <th>{t("State")}</th>
                                <th className="num">{t("Quantity")}</th>
                                <th>{t("Unit")}</th>
                                <th>{t("Reference designators")}</th>
                                <th />
                            </tr>
                        </thead>
                        <tbody>
                            {data?.map((l, i) =>
                                editing === l.id ? (
                                    <EditRow
                                        key={l.id}
                                        obj={obj}
                                        line={l}
                                        units={units}
                                        onDone={() => (
                                            setEditing(null),
                                            refresh()
                                        )}
                                    />
                                ) : (
                                    <Fragment key={l.id}>
                                        <tr
                                            className={
                                                selected.has(l.id)
                                                    ? "selected"
                                                    : undefined
                                            }
                                        >
                                            <td className="check-cell">
                                                <input
                                                    type="checkbox"
                                                    aria-label={t(
                                                        "Select line {line}",
                                                        { line: l.lineNumber },
                                                    )}
                                                    checked={selected.has(l.id)}
                                                    onChange={() =>
                                                        toggle(l.id)
                                                    }
                                                />
                                            </td>
                                            <td className="num">
                                                {l.lineNumber}
                                            </td>
                                            <td>
                                                {l.findNumber ?? (
                                                    <span className="muted">
                                                        —
                                                    </span>
                                                )}
                                            </td>
                                            <td className="icon-cell">
                                                {l.child.typeId ? (
                                                    <TypeIcon
                                                        typeId={l.child.typeId}
                                                    />
                                                ) : (
                                                    <Lock
                                                        size={14}
                                                        className="icon-muted"
                                                    />
                                                )}
                                            </td>
                                            {replacing === l.id ? (
                                                <td colSpan={4}>
                                                    <PartPicker
                                                        exclude={obj.id}
                                                        contextId={
                                                            obj.contextId
                                                        }
                                                        label={t(
                                                            "Replace with",
                                                        )}
                                                        onPick={async (
                                                            part,
                                                        ) => {
                                                            await unwrap(
                                                                api.PUT(
                                                                    "/api/v1/objects/{id}/usages/{usageId}/child",
                                                                    {
                                                                        params: {
                                                                            path: {
                                                                                id: obj.id,
                                                                                usageId:
                                                                                    l.id,
                                                                            },
                                                                        },
                                                                        body: {
                                                                            childId:
                                                                                part.id,
                                                                        },
                                                                    },
                                                                ),
                                                            );
                                                            setReplacing(null);
                                                            refresh();
                                                        }}
                                                        onCancel={() =>
                                                            setReplacing(null)
                                                        }
                                                    />
                                                </td>
                                            ) : l.child.readable ? (
                                                <>
                                                    <td className="nowrap">
                                                        <Link
                                                            className="num-link"
                                                            to={`/objects/${l.child.id}`}
                                                        >
                                                            {l.child.number}
                                                        </Link>
                                                    </td>
                                                    <td>
                                                        {l.child.name}
                                                        <ReplacementMarks
                                                            line={l}
                                                        />
                                                    </td>
                                                    <td className="version">
                                                        {l.child.version}
                                                    </td>
                                                    <td>
                                                        {l.child.state && (
                                                            <StateBadge
                                                                state={
                                                                    l.child
                                                                        .state
                                                                }
                                                            />
                                                        )}
                                                    </td>
                                                </>
                                            ) : (
                                                <td
                                                    colSpan={4}
                                                    className="muted"
                                                >
                                                    {t(
                                                        "A part you don't have access to",
                                                    )}
                                                </td>
                                            )}
                                            <td className="num">
                                                {qty(l.quantity)}
                                            </td>
                                            <td>{unitLabel(l.unit)}</td>
                                            <td className="designators">
                                                {formatDesignators(
                                                    l.designators,
                                                )}
                                            </td>
                                            <td
                                                className="nowrap"
                                                style={{ width: 1 }}
                                            >
                                                <button
                                                    className="icon-btn"
                                                    aria-label={t(
                                                        "Move line {line} up",
                                                        { line: l.lineNumber },
                                                    )}
                                                    disabled={i === 0}
                                                    onClick={() => move(i, -1)}
                                                >
                                                    <ArrowUp size={13} />
                                                </button>
                                                <button
                                                    className="icon-btn"
                                                    aria-label={t(
                                                        "Move line {line} down",
                                                        { line: l.lineNumber },
                                                    )}
                                                    disabled={
                                                        i === data.length - 1
                                                    }
                                                    onClick={() => move(i, 1)}
                                                >
                                                    <ArrowDown size={13} />
                                                </button>
                                                <button
                                                    className="icon-btn"
                                                    aria-label={t(
                                                        "Edit line {line}",
                                                        { line: l.lineNumber },
                                                    )}
                                                    onClick={() =>
                                                        setEditing(l.id)
                                                    }
                                                >
                                                    <Pencil size={13} />
                                                </button>
                                                <button
                                                    className="icon-btn"
                                                    aria-label={t(
                                                        "Replace the part on line {line}",
                                                        { line: l.lineNumber },
                                                    )}
                                                    onClick={() =>
                                                        setReplacing(l.id)
                                                    }
                                                >
                                                    <Repeat size={13} />
                                                </button>
                                                <button
                                                    className={
                                                        substituting === l.id
                                                            ? "icon-btn on"
                                                            : "icon-btn"
                                                    }
                                                    aria-label={t(
                                                        "Substitutes for line {line}",
                                                        { line: l.lineNumber },
                                                    )}
                                                    onClick={() =>
                                                        setSubstituting(
                                                            substituting ===
                                                                l.id
                                                                ? null
                                                                : l.id,
                                                        )
                                                    }
                                                >
                                                    <Shuffle size={13} />
                                                </button>
                                                <button
                                                    className="icon-btn"
                                                    aria-label={t(
                                                        "Remove line {line}",
                                                        { line: l.lineNumber },
                                                    )}
                                                    onClick={() =>
                                                        removeLine.mutate(l.id)
                                                    }
                                                >
                                                    <Trash2 size={13} />
                                                </button>
                                            </td>
                                        </tr>
                                        {substituting === l.id && (
                                            <tr className="sub-row">
                                                <td />
                                                <td colSpan={11}>
                                                    <SubstitutesEditor
                                                        obj={obj}
                                                        usageId={l.id}
                                                        onChanged={refresh}
                                                    />
                                                </td>
                                            </tr>
                                        )}
                                    </Fragment>
                                ),
                            )}
                            {data?.length === 0 && (
                                <tr>
                                    <td colSpan={12} className="empty">
                                        {t(
                                            "No parts yet: add the first one below.",
                                        )}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
            )}
            <AddLine obj={obj} units={units} onAdded={refresh} />
        </Panel>
    );
}

function EditRow({
    obj,
    line,
    units,
    onDone,
}: {
    obj: VersionedObject;
    line: UsageLine;
    units: { key: string; label: string }[];
    onDone: () => void;
}) {
    const [lineNumber, setLineNumber] = useState(String(line.lineNumber));
    const [find, setFind] = useState(line.findNumber ?? "");
    const [quantity, setQuantity] = useState(String(line.quantity));
    const [unit, setUnit] = useState(line.unit);
    const [designators, setDesignators] = useState(
        formatDesignators(line.designators),
    );
    const save = useMutation({
        mutationFn: () =>
            unwrap(
                api.PUT("/api/v1/objects/{id}/usages/{usageId}", {
                    params: { path: { id: obj.id, usageId: line.id } },
                    body: {
                        lineNumber: Number(lineNumber),
                        findNumber: find || undefined,
                        quantity: Number(quantity),
                        unit,
                        notes: line.notes ?? undefined,
                        designators: designators || undefined,
                    },
                }),
            ),
        onSuccess: onDone,
    });
    return (
        <tr className="editing">
            <td />
            <td>
                <input
                    aria-label={t("Line")}
                    className="narrow"
                    value={lineNumber}
                    onChange={(e) => setLineNumber(e.target.value)}
                    inputMode="numeric"
                />
            </td>
            <td>
                <input
                    aria-label={t("Find no.")}
                    className="narrow"
                    value={find}
                    onChange={(e) => setFind(e.target.value)}
                />
            </td>
            <td className="icon-cell">
                {line.child.typeId && <TypeIcon typeId={line.child.typeId} />}
            </td>
            <td className="nowrap">{line.child.number ?? "—"}</td>
            <td colSpan={3}>
                {line.child.name}
                {save.error != null && <ErrorBanner error={save.error} />}
            </td>
            <td>
                <input
                    aria-label={t("Quantity")}
                    className="narrow num"
                    value={quantity}
                    onChange={(e) => setQuantity(e.target.value)}
                    inputMode="decimal"
                />
            </td>
            <td>
                <select
                    aria-label={t("Unit")}
                    value={unit}
                    onChange={(e) => setUnit(e.target.value)}
                >
                    {units.map((u) => (
                        <option key={u.key} value={u.key}>
                            {u.label}
                        </option>
                    ))}
                </select>
            </td>
            <td>
                <input
                    aria-label={t("Reference designators")}
                    value={designators}
                    onChange={(e) => setDesignators(e.target.value)}
                    placeholder="R1-R4, R7"
                />
            </td>
            <td className="nowrap">
                <button
                    className="icon-btn"
                    aria-label={t("Save")}
                    onClick={() => save.mutate()}
                    disabled={save.isPending}
                >
                    <Check size={14} />
                </button>
                <button
                    className="icon-btn"
                    aria-label={t("Cancel")}
                    onClick={onDone}
                >
                    <X size={14} />
                </button>
            </td>
        </tr>
    );
}

/**
 * Finds a part by number or name; with nothing matching, offers to create one in [contextId] with
 * the typed name (roadmap 4.4).
 */
export function PartPicker({
    exclude,
    contextId,
    label,
    onPick,
    onCancel,
    allowCreate = true,
}: {
    exclude: string;
    contextId: string;
    label: string;
    allowCreate?: boolean;
    onPick: (part: VersionedObject) => void | Promise<void>;
    onCancel?: () => void;
}) {
    const [text, setText] = useState("");
    const [error, setError] = useState<unknown>();
    const { data: types } = useTypes();
    const search = useObjectSearch(text, 8);
    const isPart = (o: VersionedObject) =>
        types?.find((ty) => ty.id === o.typeId)?.baseClass === "PART";
    const matches = (search.data?.pages.flat() ?? []).filter(
        (o) => isPart(o) && o.id !== exclude,
    );
    const pick = async (o: VersionedObject) => {
        try {
            await onPick(o);
            setText("");
        } catch (e) {
            setError(e);
        }
    };
    const create = async () => {
        try {
            const part = await unwrap(
                api.POST("/api/v1/objects", {
                    body: {
                        typeId: PART_TYPE,
                        contextId,
                        name: text.trim(),
                        attributes: {},
                    },
                }),
            );
            await pick(part);
        } catch (e) {
            setError(e);
        }
    };
    return (
        <div className="add-line-pick">
            <span className="row" style={{ gap: 4 }}>
                <input
                    aria-label={label}
                    placeholder={t("Find a part by number or name")}
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    autoFocus={!!onCancel}
                />
                {onCancel && (
                    <button
                        type="button"
                        className="icon-btn"
                        aria-label={t("Cancel")}
                        onClick={onCancel}
                    >
                        <X size={13} />
                    </button>
                )}
            </span>
            {text.trim() && (
                <ul
                    className="add-line-results"
                    role="listbox"
                    aria-label={t("Matching parts")}
                >
                    {matches.length === 0 && !search.isFetching && (
                        <li className="muted">{t("No part matches")}</li>
                    )}
                    {matches.map((o) => (
                        <li
                            key={o.id}
                            role="option"
                            aria-selected={false}
                            onClick={() => void pick(o)}
                        >
                            <TypeIcon typeId={o.typeId} />{" "}
                            <strong>{o.number}</strong> {o.name}{" "}
                            <span className="muted">{o.version}</span>
                        </li>
                    ))}
                    {allowCreate && !search.isFetching && (
                        <li
                            role="option"
                            aria-selected={false}
                            className="create"
                            onClick={() => void create()}
                        >
                            <Plus size={13} aria-hidden />{" "}
                            {t("Create “{name}” as a new part", {
                                name: text.trim(),
                            })}
                        </li>
                    )}
                </ul>
            )}
            <ErrorBanner error={error} />
        </div>
    );
}

/** Find (or create) a part and add it as a new line. */
function AddLine({
    obj,
    units,
    onAdded,
}: {
    obj: VersionedObject;
    units: { key: string; label: string }[];
    onAdded: () => void;
}) {
    const [chosen, setChosen] = useState<VersionedObject | null>(null);
    const [quantity, setQuantity] = useState("1");
    const [unit, setUnit] = useState("");
    const [find, setFind] = useState("");
    const [designators, setDesignators] = useState("");
    const add = useMutation({
        mutationFn: () =>
            unwrap(
                api.POST("/api/v1/objects/{id}/usages", {
                    params: { path: { id: obj.id } },
                    body: {
                        childId: chosen!.id,
                        quantity: Number(quantity),
                        unit: unit || undefined,
                        findNumber: find || undefined,
                        designators: designators || undefined,
                    },
                }),
            ),
        onSuccess: () => {
            setChosen(null);
            setQuantity("1");
            setFind("");
            setDesignators("");
            onAdded();
        },
    });
    const submit = (e: FormEvent) => {
        e.preventDefault();
        if (chosen) add.mutate();
    };
    return (
        <form
            className="add-line"
            onSubmit={submit}
            aria-label={t("Add a part")}
        >
            {chosen ? (
                <div className="add-line-pick">
                    <span className="chosen">
                        <TypeIcon typeId={chosen.typeId} />{" "}
                        <strong>{chosen.number}</strong> {chosen.name}
                        <button
                            type="button"
                            className="icon-btn"
                            aria-label={t("Choose another part")}
                            onClick={() => setChosen(null)}
                        >
                            <X size={12} />
                        </button>
                    </span>
                </div>
            ) : (
                <PartPicker
                    exclude={obj.id}
                    contextId={obj.contextId}
                    label={t("Part to add")}
                    onPick={setChosen}
                />
            )}
            <input
                aria-label={t("Quantity")}
                className="narrow num"
                value={quantity}
                onChange={(e) => setQuantity(e.target.value)}
                inputMode="decimal"
            />
            <select
                aria-label={t("Unit")}
                value={unit}
                onChange={(e) => setUnit(e.target.value)}
            >
                <option value="">{t("Its default unit")}</option>
                {units.map((u) => (
                    <option key={u.key} value={u.key}>
                        {u.label}
                    </option>
                ))}
            </select>
            <input
                aria-label={t("Find no.")}
                className="narrow"
                placeholder={t("Find no.")}
                value={find}
                onChange={(e) => setFind(e.target.value)}
            />
            <input
                aria-label={t("Reference designators")}
                className="designators-input"
                placeholder={t("Ref. des., e.g. {example}", {
                    example: "R1-R4",
                })}
                value={designators}
                onChange={(e) => setDesignators(e.target.value)}
            />
            <button
                className="btn primary"
                type="submit"
                disabled={!chosen || add.isPending}
            >
                <Plus size={14} aria-hidden /> {t("Add")}
            </button>
            {add.error != null && (
                <div style={{ flexBasis: "100%" }}>
                    <ErrorBanner error={add.error} />
                </div>
            )}
        </form>
    );
}

/** Small marks on a line: its substitutes, its part's alternates (roadmap 4.8). */
export function ReplacementMarks({
    line,
}: {
    line: Pick<UsageLine, "substitutes" | "alternates">;
}) {
    return (
        <>
            {line.substitutes > 0 && (
                <span
                    className="tag small-tag"
                    title={t("Substitutes on this line")}
                >
                    {tn("{n} substitute", "{n} substitutes", line.substitutes)}
                </span>
            )}
            {line.alternates > 0 && (
                <span
                    className="tag small-tag"
                    title={t("Alternates of this part")}
                >
                    {tn("{n} alternate", "{n} alternates", line.alternates)}
                </span>
            )}
        </>
    );
}
