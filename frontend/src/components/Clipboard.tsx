import { ClipboardList, X } from "lucide-react";
import { useSyncExternalStore } from "react";
import { Link } from "react-router";
import { MenuButton } from "./common";

/** An object kept at hand (roadmap 3.4), to paste into a folder: moved or copied. */
export type ClipItem = { id: string; number: string; name: string; version: string };

const KEY = "plm.clipboard";
const EVENT = "plm-clipboard";

function read(): ClipItem[] {
  try {
    return JSON.parse(localStorage.getItem(KEY) ?? "[]") as ClipItem[];
  } catch {
    return [];
  }
}

let cache = read();
function write(items: ClipItem[]) {
  cache = items;
  try {
    localStorage.setItem(KEY, JSON.stringify(items));
  } catch {
    /* private mode: the clipboard lasts for this page */
  }
  window.dispatchEvent(new Event(EVENT));
}

function subscribe(cb: () => void) {
  const onStorage = (e: StorageEvent) => {
    if (e.key === KEY) {
      cache = read();
      cb();
    }
  };
  window.addEventListener(EVENT, cb);
  window.addEventListener("storage", onStorage);
  return () => {
    window.removeEventListener(EVENT, cb);
    window.removeEventListener("storage", onStorage);
  };
}

export function useClipboard() {
  const items = useSyncExternalStore(subscribe, () => cache);
  return {
    items,
    add: (item: ClipItem) => write([...cache.filter((i) => i.id !== item.id), item]),
    remove: (id: string) => write(cache.filter((i) => i.id !== id)),
    clear: () => write([]),
  };
}

/** The clipboard in the top bar: what's on it, to open or take off. */
export function ClipboardButton() {
  const { items, remove, clear } = useClipboard();
  if (!items.length) return null;
  return (
    <MenuButton label={`Clipboard (${items.length})`} icon={<ClipboardList size={15} aria-hidden />} className="topbar-link clip-btn">
      {(close) => (
        <div className="clipboard-menu">
          <div className="menu-label">On the clipboard: paste into a folder from its Contents</div>
          {items.map((i) => (
            <div key={i.id} className="clip-row">
              <Link to={`/objects/${i.id}`} onClick={close}>
                <strong>{i.number}</strong> {i.name} <span className="muted">{i.version}</span>
              </Link>
              <button className="icon-btn" aria-label={`Take ${i.number} off the clipboard`} onClick={() => remove(i.id)}>
                <X size={13} />
              </button>
            </div>
          ))}
          <hr />
          <button role="menuitem" onClick={() => (clear(), close())}>
            Clear the clipboard
          </button>
        </div>
      )}
    </MenuButton>
  );
}
