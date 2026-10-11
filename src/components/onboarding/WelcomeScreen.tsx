import { useState, useCallback } from "react";
import {
  motion,
  AnimatePresence,
  useReducedMotion,
  type Variants,
} from "framer-motion";
import { FolderOpen, FolderPlus, ArrowLeft, Sparkles } from "lucide-react";
import { openDialog, isAndroid, getDocumentDir } from "@/lib/host";
import { TitleBar } from "@/components/layout/TitleBar";
import { LanguageSwitcher } from "@/components/layout/LanguageSwitcher";
import { useMacTopChromeEnabled } from "@/components/layout/MacTopChrome";
import { WindowControls } from "@/components/layout/WindowControls";
import { Button } from "@/components/ui/button";
import { ActionCard } from "./ActionCard";
import { RecentVaultList } from "./RecentVaultList";
import { useRecentVaultStore } from "@/stores/useRecentVaultStore";
import { resolveRendererAssetUrl } from "@/lib/appAsset";
import { useLocaleStore } from "@/stores/useLocaleStore";

interface WelcomeScreenProps {
  onOpenVault: (path?: string) => void;
  onCreateVault?: (name: string) => void;
}

const containerVariants: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.08 } },
};

const fadeUpVariants: Variants = {
  hidden: { opacity: 0, y: 12 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.28, ease: [0.2, 0.9, 0.1, 1] },
  },
};

const viewVariants = {
  enter: { opacity: 0, y: 8 },
  center: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -8 },
};

export function WelcomeScreen({
  onOpenVault,
  onCreateVault,
}: WelcomeScreenProps) {
  const { t } = useLocaleStore();
  const showMacWindowInset = useMacTopChromeEnabled();
  const prefersReducedMotion = useReducedMotion();
  const logoUrl = resolveRendererAssetUrl("lumina.png");
  const onMobileDevice = isAndroid();

  const vaults = useRecentVaultStore((s) => s.vaults);
  const removeVault = useRecentVaultStore((s) => s.removeVault);
  const clearVaults = useRecentVaultStore((s) => s.clearVaults);

  const [view, setView] = useState<"welcome" | "create">("welcome");
  const [vaultName, setVaultName] = useState("");

  const handleOpenExisting = useCallback(async () => {
    try {
      const selected = await openDialog({
        directory: true,
        multiple: false,
        title: t.welcome.openFolder,
      });
      if (selected && typeof selected === "string") {
        onOpenVault(selected);
      }
    } catch (error) {
      console.error("[WelcomeScreen] Open folder dialog failed:", error);
    }
  }, [onOpenVault, t.welcome.openFolder]);

  const handleOpenDefaultMobileVault = useCallback(async () => {
    try {
      const docDir = await getDocumentDir();
      if (docDir) {
        onOpenVault(docDir);
      } else {
        onOpenVault();
      }
    } catch (e) {
      console.error("[WelcomeScreen] Default vault open failed:", e);
      onOpenVault();
    }
  }, [onOpenVault]);

  const handleShowCreate = useCallback(() => {
    setVaultName("");
    setView("create");
  }, []);

  const handleBack = useCallback(() => {
    setVaultName("");
    setView("welcome");
  }, []);

  const handleCreateSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      const trimmed = vaultName.trim();
      if (trimmed && onCreateVault) {
        onCreateVault(trimmed);
      }
    },
    [vaultName, onCreateVault],
  );

  return (
    <div className="h-full flex flex-col bg-background">
      <TitleBar />

      <div className="relative flex-1 overflow-hidden flex flex-col">
        {showMacWindowInset ? (
          <div
            className="flex items-center px-4 py-2"
            data-tauri-drag-region
            data-testid="welcome-top-row"
          >
            <div
              className="w-16 flex justify-center shrink-0"
              data-tauri-drag-region="false"
            >
              <WindowControls />
            </div>
            <div className="flex-1" />
            <LanguageSwitcher compact stopPropagation />
          </div>
        ) : (
          <div className="absolute top-[max(0.75rem,var(--sat))] right-4 z-20">
            <LanguageSwitcher showLabel={false} />
          </div>
        )}

        <div className="flex-1 flex flex-col md:flex-row min-h-0 overflow-y-auto md:overflow-hidden">
          {/* Left sidebar: Recent vaults (hidden on small/mobile screens) */}
          <div className="hidden md:flex w-[320px] shrink-0 border-r border-border flex-col">
            <RecentVaultList
              vaults={vaults}
              onSelect={(path) => onOpenVault(path)}
              onRemove={removeVault}
              onClear={clearVaults}
            />
          </div>

          {/* Right/Main pane */}
          <div className="flex-1 flex items-center justify-center px-4 py-8 md:px-10 md:py-10 overflow-y-auto">
            <motion.div
              variants={containerVariants}
              initial={prefersReducedMotion ? "visible" : "hidden"}
              animate="visible"
              className="flex w-full max-w-[460px] md:max-w-[560px] md:-translate-y-7 flex-col"
            >
              {/* Logo & Title */}
              <motion.div
                variants={fadeUpVariants}
                className="mb-8 md:mb-[56px] flex items-center gap-4 md:gap-[22px] justify-center md:justify-start"
              >
                <img
                  src={logoUrl}
                  alt="Lumina Note"
                  className="h-14 w-14 md:h-[68px] md:w-[68px] rounded-ui-xl shadow-sm"
                />

                <h1 className="text-2xl md:text-[36px] font-semibold leading-tight tracking-normal text-foreground">
                  {t.welcome.title}
                </h1>
              </motion.div>

              <div className="min-h-[150px]">
                <AnimatePresence mode="wait">
                  {view === "welcome" ? (
                    <motion.div
                      key="welcome"
                      variants={fadeUpVariants}
                      initial={prefersReducedMotion ? "visible" : "hidden"}
                      animate="visible"
                      exit={
                        prefersReducedMotion
                          ? {}
                          : { opacity: 0, transition: { duration: 0.15 } }
                      }
                      className="w-full flex flex-col gap-3 md:gap-[18px]"
                    >
                      {onMobileDevice && (
                        <ActionCard
                          icon={Sparkles}
                          title="Quick Start (Default Vault)"
                          action={{
                            label: t.common.open,
                            variant: "primary",
                            onClick: handleOpenDefaultMobileVault,
                          }}
                        />
                      )}

                      <ActionCard
                        icon={FolderOpen}
                        title={t.welcome.openFolder}
                        action={{
                          label: t.common.open,
                          variant: onMobileDevice ? "secondary" : "primary",
                          onClick: handleOpenExisting,
                        }}
                      />

                      {onCreateVault && (
                        <ActionCard
                          icon={FolderPlus}
                          title={t.welcome.createVault}
                          action={{
                            label: t.welcome.newVaultButton,
                            variant: "secondary",
                            onClick: handleShowCreate,
                          }}
                        />
                      )}

                      {/* Mobile Recent Vaults List */}
                      {vaults.length > 0 && (
                        <div className="mt-4 md:hidden">
                          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                            {t.welcome.recentVaults}
                          </h3>
                          <div className="flex flex-col gap-1.5 max-h-[160px] overflow-y-auto">
                            {vaults.slice(0, 3).map((v) => (
                              <button
                                key={v.path}
                                type="button"
                                onClick={() => onOpenVault(v.path)}
                                className="flex items-center justify-between p-3 rounded-ui-lg border border-border/70 bg-muted/40 text-left hover:bg-accent active:scale-[0.99] transition-all"
                              >
                                <span className="truncate font-medium text-foreground text-sm">
                                  {v.name}
                                </span>
                                <span className="text-xs text-muted-foreground truncate max-w-[140px] ml-2">
                                  {v.path}
                                </span>
                              </button>
                            ))}
                          </div>
                        </div>
                      )}
                    </motion.div>
                  ) : (
                    <motion.form
                      key="create"
                      onSubmit={handleCreateSubmit}
                      variants={viewVariants}
                      initial={prefersReducedMotion ? "center" : "enter"}
                      animate="center"
                      exit={
                        prefersReducedMotion
                          ? {}
                          : { opacity: 0, transition: { duration: 0.15 } }
                      }
                      transition={{ duration: 0.2, ease: [0.2, 0.9, 0.1, 1] }}
                      className="w-full rounded-ui-xl bg-muted/55 p-3"
                    >
                      <div className="mb-3 flex items-start justify-between gap-4">
                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <FolderPlus className="h-4 w-4 shrink-0 text-muted-foreground" />
                            <h2 className="text-base font-semibold leading-6 tracking-normal text-foreground">
                              {t.welcome.createVault}
                            </h2>
                          </div>
                          <p className="mt-1 max-w-[420px] text-sm text-muted-foreground">
                            {t.welcome.createVaultDesc}
                          </p>
                        </div>

                        <button
                          type="button"
                          onClick={handleBack}
                          className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-ui-md px-2 text-sm text-muted-foreground transition-colors hover:bg-background/70 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/20"
                        >
                          <ArrowLeft className="h-4 w-4" />
                          {t.common.cancel}
                        </button>
                      </div>

                      <div className="flex h-11 w-full items-center gap-2 rounded-ui-lg bg-background px-2 shadow-[inset_0_0_0_1px_hsl(var(--border)/0.58)]">
                        <input
                          type="text"
                          value={vaultName}
                          onChange={(e) => setVaultName(e.target.value)}
                          placeholder={t.welcome.vaultNamePlaceholder}
                          className="h-full min-w-0 flex-1 appearance-none border-0 bg-transparent px-1 text-sm text-foreground placeholder:text-muted-foreground outline-none ring-0 focus:outline-none focus:ring-0 focus-visible:outline-none"
                          autoFocus
                        />
                        <Button
                          variant="primary"
                          size="sm"
                          type="submit"
                          disabled={!vaultName.trim()}
                          className="h-8 shrink-0 px-4"
                        >
                          {t.common.create}
                        </Button>
                      </div>
                    </motion.form>
                  )}
                </AnimatePresence>
              </div>
            </motion.div>
          </div>
        </div>
      </div>
    </div>
  );
}
