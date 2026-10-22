import { createContext, useContext } from 'react';
import { displayName, withNames } from './format';

/**
 * Real names on screen, pseudonyms everywhere else. The table comes from this computer's server for
 * this run only; nothing here is saved, and saved reports keep their pseudonyms.
 */
export interface Names {
  show: boolean;
  table: Record<string, string>;
}

export const NamesContext = createContext<Names>({ show: false, table: {} });

/** A function that shows a pseudonym as the person's real name when names are switched on. */
export function useName(): (label: string) => string {
  const { show, table } = useContext(NamesContext);
  return (label: string) => displayName(label, table, show);
}

/** The same for pseudonyms inside a sentence or a piece of server-escaped HTML. */
export function useNamedText(): (text: string) => string {
  const { show, table } = useContext(NamesContext);
  return (text: string) => withNames(text, table, show);
}
