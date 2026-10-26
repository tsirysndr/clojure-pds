// Custom SDK bootstrap. User modules, code generation, and dispatch are Clojure.
package main

import (
	"context"
	"fmt"
	"path"
	"path/filepath"
	"strings"

	"clojure-sdk/internal/dagger"
)

type ClojureSdk struct {
	Source *dagger.Directory
}

// FindClientRoot locates the nearest Clojure package.
func (m *ClojureSdk) FindClientRoot(ctx context.Context, ws *dagger.Workspace) (*string, error) {
	marker, err := ws.FindUp(ctx, "deps.edn")
	if err != nil || marker == "" {
		return nil, err
	}
	root := path.Dir(marker)
	return &root, nil
}

// GenerateScope writes configuration and dependency bindings before execution.
func (m *ClojureSdk) GenerateScope(ctx context.Context, ws *dagger.Workspace, isModule bool, name string, clients []*dagger.ModuleSource) (*dagger.Workspace, error) {
	if !isModule {
		return nil, fmt.Errorf("standalone scopes are not supported yet; use a Clojure module scope")
	}
	cwd, err := ws.Cwd(ctx)
	if err != nil {
		return nil, err
	}
	ref, err := ws.SDK("clojure").Ref(ctx)
	if err != nil {
		return nil, err
	}
	if strings.HasPrefix(ref, ".") || strings.HasPrefix(ref, "/") || !strings.ContainsAny(strings.Split(ref, "/")[0], ".:@") {
		ref, err = filepath.Rel(path.Join("/", cwd), path.Join("/", ref))
		if err != nil {
			return nil, err
		}
	}
	exists, err := ws.Directory(".").Exists(ctx, "dagger-module.toml")
	if err != nil {
		return nil, err
	}
	if !exists {
		ws = ws.WithNewFile("dagger-module.toml", fmt.Sprintf("name = %q\nengineVersion = \"v1.0.0-beta.21\"\n[runtime]\nsource = %q\n", name, ref))
	}
	src := ws.ModuleSource("/" + strings.TrimPrefix(cwd, "/")).WithName(name).WithSDK(ref).WithDependencies(clients)
	root, err := src.SourceRootSubpath(ctx)
	if err != nil {
		return nil, err
	}
	return ws.WithDirectory(".", src.GeneratedContextDirectory().Directory(root)), nil
}

func New(ctx context.Context) (*ClojureSdk, error) {
	subpath, err := dag.CurrentModule().SourceSubpath(ctx)
	if err != nil {
		return nil, err
	}
	return &ClojureSdk{Source: dag.CurrentModule().ContextDirectory().Directory(path.Dir(subpath))}, nil
}

func (m *ClojureSdk) Codegen(ctx context.Context, modSource *dagger.ModuleSource, introspectionJSON *dagger.File) (*dagger.GeneratedCode, error) {
	ctr, subpath, err := m.prepare(ctx, modSource, introspectionJSON)
	if err != nil {
		return nil, err
	}
	return dag.GeneratedCode(ctr.Directory("/src").
		WithoutDirectory(path.Join(subpath, ".cpcache")).
		WithoutDirectory(path.Join(subpath, "target"))).
		WithVCSGeneratedPaths([]string{"dagger-sdk/**"}).
		WithVCSIgnoredPaths([]string{".cpcache", "target"}), nil
}

func (m *ClojureSdk) ModuleRuntime(ctx context.Context, modSource *dagger.ModuleSource,
	// +optional
	introspectionJSON *dagger.File,
) (*dagger.Container, error) {
	ctr, subpath, err := m.prepare(ctx, modSource, introspectionJSON)
	if err != nil {
		return nil, err
	}
	return ctr.WithExec([]string{"clojure", "-P"}).
		WithExec([]string{"clojure", "-M", "-e", "(require 'dagger-main)"}).
		WithEntrypoint([]string{"sh", "-c", `cd "$1" && shift && exec clojure -M -m dagger-main "$@"`, "clojure-module", path.Join("/src", subpath)}), nil
}

func (m *ClojureSdk) prepare(ctx context.Context, modSource *dagger.ModuleSource, introspectionJSON *dagger.File) (*dagger.Container, string, error) {
	subpath, err := modSource.SourceSubpath(ctx)
	if err != nil {
		return nil, "", err
	}
	if path.IsAbs(subpath) || subpath == ".." || strings.HasPrefix(path.Clean(subpath), "../") {
		return nil, "", fmt.Errorf("invalid module source path: %q", subpath)
	}
	workdir := path.Join("/src", subpath)
	name, err := modSource.ModuleOriginalName(ctx)
	if err != nil {
		return nil, "", err
	}
	ctr := dag.Container().From("eclipse-temurin:21-jdk-jammy").
		WithExec([]string{"sh", "-c", "apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git && rm -rf /var/lib/apt/lists/*"}).
		WithDirectory("/sdk", m.Source).
		WithExec([]string{"sh", "/sdk/runtime/install-clojure.sh"}).
		WithMountedCache("/root/.m2", dag.CacheVolume("clojure-sdk-maven")).
		WithWorkdir("/sdk")
	if introspectionJSON != nil {
		ctr = ctr.WithFile("/schema.json", introspectionJSON).
			WithExec([]string{"clojure", "-M:generate", "/schema.json"})
	}
	sdk := ctr.Directory("/sdk").WithoutDirectory("runtime").WithoutDirectory("examples").WithoutDirectory("test").
		WithoutDirectory(".cpcache").WithoutDirectory("target").WithoutDirectory("schema")
	// Runtime loading may omit introspection and generated files may be ignored.
	// Keep generated dependency bindings when available; otherwise install the
	// bundled core API so a clean checkout can register the module.
	if introspectionJSON == nil {
		exists, err := modSource.ContextDirectory().Exists(ctx, path.Join(subpath, "dagger-sdk", "deps.edn"))
		if err != nil {
			return nil, "", fmt.Errorf("check generated SDK: %w", err)
		}
		if exists {
			sdk = modSource.ContextDirectory().Directory(path.Join(subpath, "dagger-sdk"))
		}
	}
	source := modSource.ContextDirectory().WithoutDirectory(path.Join(subpath, "dagger-sdk")).
		WithoutDirectory(path.Join(subpath, ".cpcache")).WithoutDirectory(path.Join(subpath, "target"))
	ctr = ctr.WithDirectory("/src", source).
		WithDirectory(path.Join(workdir, "dagger-sdk"), sdk)
	// Use -Sdeps to add dev tooling without selecting the codegen main alias.
	ctr = ctr.WithExec([]string{"clojure", "-Sdeps", "{:paths [\"src\" \"resources\" \"dev\"]}", "-M", "-m", "dagger.scaffold", workdir, name, "/sdk/runtime/template/src/dagger_main.clj"}).
		WithWorkdir(workdir)
	return ctr, subpath, nil
}
