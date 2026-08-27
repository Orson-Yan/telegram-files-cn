"use client";

import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import {
  DEFAULT_LOCALE,
  LANGUAGE_STORAGE_KEY,
  type Locale,
  translateText,
} from "@/i18n/messages";

interface LanguageContextValue {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  toggleLocale: () => void;
}

const LanguageContext = createContext<LanguageContextValue | null>(null);

const originalText = new WeakMap<Text, string>();
const appliedText = new WeakMap<Text, string>();
const originalAttributes = new WeakMap<Element, Map<string, string>>();
const appliedAttributes = new WeakMap<Element, Map<string, string>>();

const TRANSLATED_ATTRIBUTES = [
  "aria-label",
  "aria-description",
  "alt",
  "placeholder",
  "title",
] as const;

function isSkipped(element: Element | null): boolean {
  return (
    !element ||
    element.closest(
      "script, style, code, pre, [data-i18n-skip], [translate='no']",
    ) !== null
  );
}

function localizeTextNode(node: Text, locale: Locale) {
  if (isSkipped(node.parentElement)) {
    return;
  }

  const current = node.nodeValue ?? "";
  if (appliedText.get(node) !== current) {
    originalText.set(node, current);
  }

  const source = originalText.get(node) ?? current;
  const translated = translateText(source, locale);
  if (current !== translated) {
    node.nodeValue = translated;
  }
  appliedText.set(node, translated);
}

function localizeAttributes(element: Element, locale: Locale) {
  if (isSkipped(element)) {
    return;
  }

  let sources = originalAttributes.get(element);
  let applied = appliedAttributes.get(element);
  if (!sources) {
    sources = new Map();
    originalAttributes.set(element, sources);
  }
  if (!applied) {
    applied = new Map();
    appliedAttributes.set(element, applied);
  }

  for (const attribute of TRANSLATED_ATTRIBUTES) {
    const current = element.getAttribute(attribute);
    if (current === null) {
      continue;
    }

    if (applied.get(attribute) !== current) {
      sources.set(attribute, current);
    }

    const translated = translateText(sources.get(attribute) ?? current, locale);
    if (current !== translated) {
      element.setAttribute(attribute, translated);
    }
    applied.set(attribute, translated);
  }
}

function localizeSubtree(root: Node, locale: Locale) {
  if (root instanceof Text) {
    localizeTextNode(root, locale);
    return;
  }
  if (!(root instanceof Element) && !(root instanceof Document)) {
    return;
  }

  if (root instanceof Element) {
    localizeAttributes(root, locale);
  }

  const walker = document.createTreeWalker(
    root,
    NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT,
  );
  let node = walker.nextNode();
  while (node) {
    if (node instanceof Text) {
      localizeTextNode(node, locale);
    } else if (node instanceof Element) {
      localizeAttributes(node, locale);
    }
    node = walker.nextNode();
  }
}

function detectLocale(): Locale {
  const stored = window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
  if (stored === "en" || stored === "zh-CN") {
    return stored;
  }
  return window.navigator.language.toLowerCase().startsWith("zh")
    ? "zh-CN"
    : DEFAULT_LOCALE;
}

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(DEFAULT_LOCALE);

  useEffect(() => {
    setLocaleState(detectLocale());
  }, []);

  const setLocale = useCallback((nextLocale: Locale) => {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, nextLocale);
    setLocaleState(nextLocale);
  }, []);

  const toggleLocale = useCallback(() => {
    setLocale(locale === "zh-CN" ? "en" : "zh-CN");
  }, [locale, setLocale]);

  useEffect(() => {
    document.documentElement.lang = locale === "zh-CN" ? "zh-CN" : "en";
    document.title =
      locale === "zh-CN"
        ? "Telegram Files - Telegram 文件管理器"
        : "Telegram Files";

    const description = document.querySelector<HTMLMetaElement>(
      'meta[name="description"]',
    );
    if (description) {
      description.content =
        locale === "zh-CN"
          ? "自托管的 Telegram 文件下载与管理工具"
          : "Manage your files on Telegram";
    }

    const root = document.body;
    const observer = new MutationObserver((records) => {
      observer.disconnect();
      for (const record of records) {
        if (record.type === "characterData") {
          localizeSubtree(record.target, locale);
        } else if (record.type === "attributes") {
          localizeAttributes(record.target as Element, locale);
        } else {
          record.addedNodes.forEach((node) => localizeSubtree(node, locale));
        }
      }
      observe();
    });

    const observe = () => {
      observer.observe(root, {
        subtree: true,
        childList: true,
        characterData: true,
        attributes: true,
        attributeFilter: [...TRANSLATED_ATTRIBUTES],
      });
    };

    localizeSubtree(root, locale);
    observe();
    return () => observer.disconnect();
  }, [locale]);

  const value = useMemo(
    () => ({ locale, setLocale, toggleLocale }),
    [locale, setLocale, toggleLocale],
  );

  return (
    <LanguageContext.Provider value={value}>
      {children}
    </LanguageContext.Provider>
  );
}

export function useLanguage(): LanguageContextValue {
  const context = useContext(LanguageContext);
  if (!context) {
    throw new Error("useLanguage must be used within LanguageProvider");
  }
  return context;
}
